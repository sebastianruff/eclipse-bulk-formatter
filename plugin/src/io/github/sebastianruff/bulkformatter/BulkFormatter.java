package io.github.sebastianruff.bulkformatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.ui.IEditorDescriptor;

/**
 * Formats files of any type with the formatter Eclipse would use for them:
 * <ol>
 * <li>Java source files: headlessly with the project's Java formatter settings</li>
 * <li>files whose default editor has a formatter: with that editor (opened in the background if necessary)</li>
 * <li>files handled by a language server (LSP4E): with the server's formatting</li>
 * </ol>
 * Binary files and files without any formatter are skipped.
 */
public final class BulkFormatter {

	/** Outcome of a bulk format run. */
	public static final class Result {
		public final List<IFile> changed = new ArrayList<>();
		public final List<IFile> unchanged = new ArrayList<>();
		/** Files without a formatter, e.g. binary files or plain text. */
		public final List<IFile> skipped = new ArrayList<>();
		public final Map<IFile, String> failed = new LinkedHashMap<>();

		public int total() {
			return changed.size() + unchanged.size() + skipped.size() + failed.size();
		}
	}

	private BulkFormatter() {
	}

	/**
	 * Formats the given files. Files that are open in a dirty editor are formatted in the editor but not saved, so
	 * unsaved user changes are never written to disk implicitly. Must not be called in the UI thread.
	 *
	 * @throws org.eclipse.core.runtime.OperationCanceledException if the monitor is canceled
	 */
	public static Result format(Collection<IFile> files, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, "Formatting files", files.size());
		JavaStrategy java = new JavaStrategy();
		LspStrategy lsp = LspStrategy.isAvailable() ? new LspStrategy() : null;
		Result result = new Result();
		for (IFile file : files) {
			progress.subTask(file.getFullPath().toString());
			try {
				switch (formatFile(file, java, lsp, progress.split(1))) {
				case CHANGED -> result.changed.add(file);
				case UNCHANGED -> result.unchanged.add(file);
				case NOT_SUPPORTED -> result.skipped.add(file);
				}
			} catch (CoreException | MalformedTreeException | BadLocationException e) {
				result.failed.put(file, e.getMessage());
			}
		}
		return result;
	}

	/** @param lsp null if LSP4E is not installed */
	private static Outcome formatFile(IFile file, JavaStrategy java, LspStrategy lsp, IProgressMonitor monitor)
			throws CoreException, BadLocationException {
		if (!file.isAccessible() || !isText(file)) {
			return Outcome.NOT_SUPPORTED;
		}
		ICompilationUnit unit = JavaStrategy.asCompilationUnit(file);
		if (unit != null) {
			return java.format(unit, monitor);
		}
		IEditorDescriptor editor = EditorStrategy.defaultEditor(file);
		if (EditorStrategy.formatsViaLanguageServer(editor)) {
			return lsp != null ? lsp.format(file, monitor) : Outcome.NOT_SUPPORTED;
		}
		Outcome outcome = EditorStrategy.format(file, editor);
		if (outcome == Outcome.NOT_SUPPORTED && lsp != null) {
			outcome = lsp.format(file, monitor);
		}
		return outcome;
	}

	private static boolean isText(IFile file) throws CoreException {
		IContentDescription description = file.getContentDescription();
		IContentType text = Platform.getContentTypeManager().getContentType(IContentTypeManager.CT_TEXT);
		return description != null && description.getContentType() != null
				&& description.getContentType().isKindOf(text);
	}
}
