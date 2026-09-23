package io.github.sebastianruff.bulkformatter;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;

/**
 * Formats a file headlessly through a language server (LSP4E), like the Generic Editor's Format command does. Covers
 * everything Wild Web Developer and other LSP based plugins support: JSON, YAML, XML, CSS, HTML, JavaScript,
 * TypeScript, ... Only usable when LSP4E is installed, see {@link #isAvailable()}.
 */
final class LspStrategy {

	/** Starting a language server (e.g. node based ones) can take a while on first use. */
	private static final long TIMEOUT_SECONDS = 60;

	/**
	 * Some servers (e.g. the YAML server) register their formatting capability dynamically shortly after starting.
	 * How long to keep asking a freshly started server before concluding it cannot format.
	 */
	private static final long CAPABILITY_WAIT_MILLIS = 3_000;

	private static final boolean AVAILABLE = detect();

	/** Content types whose servers were already given time to register formatting in this run. */
	private final Set<String> waitedFor = new HashSet<>();

	static boolean isAvailable() {
		return AVAILABLE;
	}

	private static boolean detect() {
		try {
			Class.forName("org.eclipse.lsp4e.LanguageServers", false, LspStrategy.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	/** Wraps the edits so that "no edits" (already formatted) is distinguishable from "no server". */
	private record Edits(List<? extends TextEdit> edits) {
	}

	Outcome format(IFile file, IProgressMonitor monitor) throws CoreException {
		SubMonitor progress = SubMonitor.convert(monitor, 3);
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		IPath path = file.getFullPath();
		manager.connect(path, LocationKind.IFILE, progress.split(1));
		try {
			ITextFileBuffer buffer = manager.getTextFileBuffer(path, LocationKind.IFILE);
			IDocument document = buffer.getDocument();
			URI uri = LSPEclipseUtils.toUri(file);
			if (uri == null) {
				return Outcome.NOT_SUPPORTED;
			}
			Optional<Edits> result = requestFormatting(document, uri);
			if (result.isEmpty() && shouldWaitForCapabilities(file, document)) {
				long deadline = System.currentTimeMillis() + CAPABILITY_WAIT_MILLIS;
				while (result.isEmpty() && System.currentTimeMillis() < deadline && !progress.isCanceled()) {
					sleep(250);
					result = requestFormatting(document, uri);
				}
			}
			if (result.isEmpty()) {
				return Outcome.NOT_SUPPORTED;
			}
			List<? extends TextEdit> edits = result.get().edits();
			if (edits.isEmpty()) {
				return Outcome.UNCHANGED;
			}
			if (file.isReadOnly()) {
				throw new CoreException(Status.error("File is read-only"));
			}
			boolean wasDirty = buffer.isDirty();
			String before = document.get();
			applyInUiThread(document, edits);
			if (document.get().equals(before)) {
				return Outcome.UNCHANGED;
			}
			if (!wasDirty) {
				buffer.commit(progress.split(1), false);
			}
			return Outcome.CHANGED;
		} finally {
			manager.disconnect(path, LocationKind.IFILE, progress.split(1));
		}
	}

	/** Only wait once per content type, and only if a server for the file exists at all. */
	private boolean shouldWaitForCapabilities(IFile file, IDocument document) throws CoreException {
		IContentDescription description = file.getContentDescription();
		String contentType = description == null || description.getContentType() == null ? file.getFileExtension()
				: description.getContentType().getId();
		return waitedFor.add(String.valueOf(contentType)) && LanguageServers.forDocument(document).anyMatching();
	}

	private static void sleep(long millis) throws CoreException {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(Status.error("Interrupted while waiting for the language server", e));
		}
	}

	private static Optional<Edits> requestFormatting(IDocument document, URI uri) throws CoreException {
		FormattingOptions options = formattingOptions();
		TextDocumentIdentifier id = new TextDocumentIdentifier(uri.toString());
		Range wholeDocument;
		try {
			wholeDocument = LSPEclipseUtils.toRange(0, document.getLength(), document);
		} catch (BadLocationException e) {
			throw new CoreException(Status.error("Cannot determine document range", e));
		}
		CompletableFuture<Optional<Edits>> request = LanguageServers.forDocument(document)
				.withFilter(LspStrategy::supportsFormatting)
				.computeFirst((wrapper, server) -> wrapper.getServerCapabilitiesAsync().thenCompose(capabilities -> {
					if (capabilities == null) {
						return CompletableFuture.completedFuture(null);
					}
					CompletableFuture<List<? extends TextEdit>> edits;
					if (LSPEclipseUtils.hasCapability(capabilities.getDocumentFormattingProvider())) {
						edits = server.getTextDocumentService().formatting(new DocumentFormattingParams(id, options));
					} else {
						DocumentRangeFormattingParams params = new DocumentRangeFormattingParams(id, options,
								wholeDocument);
						edits = server.getTextDocumentService().rangeFormatting(params);
					}
					return edits.thenApply(list -> new Edits(list == null ? List.of() : list));
				}));
		try {
			return request.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CoreException(Status.error("Interrupted while waiting for the language server", e));
		} catch (ExecutionException e) {
			throw new CoreException(Status.error("Language server failed: " + e.getCause().getMessage(), e));
		} catch (TimeoutException e) {
			request.cancel(true);
			throw new CoreException(Status.error("Language server did not answer within " + TIMEOUT_SECONDS + "s"));
		}
	}

	private static boolean supportsFormatting(ServerCapabilities capabilities) {
		return LSPEclipseUtils.hasCapability(capabilities.getDocumentFormattingProvider())
				|| LSPEclipseUtils.hasCapability(capabilities.getDocumentRangeFormattingProvider());
	}

	/** Same options the Generic Editor sends: the workspace text editor settings. */
	private static FormattingOptions formattingOptions() {
		IPreferenceStore store = EditorsUI.getPreferenceStore();
		return new FormattingOptions(store.getInt(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_TAB_WIDTH),
				store.getBoolean(AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SPACES_FOR_TABS));
	}

	/** The document may be shown in an editor, so it must only be modified in the UI thread. */
	private static void applyInUiThread(IDocument document, List<? extends TextEdit> edits) throws CoreException {
		AtomicReference<BadLocationException> failure = new AtomicReference<>();
		PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
			try {
				LSPEclipseUtils.applyEdits(document, edits);
			} catch (BadLocationException e) {
				failure.set(e);
			}
		});
		if (failure.get() != null) {
			throw new CoreException(Status.error("Invalid edits from language server", failure.get()));
		}
	}
}
