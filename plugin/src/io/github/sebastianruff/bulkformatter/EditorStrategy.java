package io.github.sebastianruff.bulkformatter;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Formats a file with the formatter of its default editor, exactly like pressing Ctrl+Shift+F in that editor: the
 * editor is opened in the background (unless it is already open), formatted, saved and closed again. Works for every
 * editor that offers a formatter (XML, HTML, CSS, JSP, C/C++, ...).
 */
final class EditorStrategy {

	private static final String GENERIC_EDITOR_ID = "org.eclipse.ui.genericeditor.GenericEditor";

	private EditorStrategy() {
	}

	/** @return the internal editor Eclipse would open the file with, or null if it would use an external program */
	static IEditorDescriptor defaultEditor(IFile file) throws CoreException {
		return syncExec(() -> {
			IEditorDescriptor descriptor = IDE.getEditorDescriptor(file, true, false);
			return descriptor != null && descriptor.isInternal() ? descriptor : null;
		});
	}

	/** The Generic Editor formats through language servers only, so there is no point in opening it. */
	static boolean formatsViaLanguageServer(IEditorDescriptor editor) {
		return editor == null || GENERIC_EDITOR_ID.equals(editor.getId())
				|| EditorsUI.DEFAULT_TEXT_EDITOR_ID.equals(editor.getId());
	}

	static Outcome format(IFile file, IEditorDescriptor descriptor) throws CoreException {
		return syncExec(() -> formatInUiThread(file, descriptor));
	}

	private static Outcome formatInUiThread(IFile file, IEditorDescriptor descriptor) throws CoreException {
		IWorkbenchPage page = page();
		FileEditorInput input = new FileEditorInput(file);
		IEditorPart editor = page.findEditor(input);
		boolean openedHere = editor == null;
		if (openedHere) {
			editor = IDE.openEditor(page, file, descriptor.getId(), false);
		}
		try {
			ITextOperationTarget target = Adapters.adapt(editor, ITextOperationTarget.class);
			if (target == null || !target.canDoOperation(ISourceViewer.FORMAT)) {
				return Outcome.NOT_SUPPORTED;
			}
			if (file.isReadOnly()) {
				throw new CoreException(Status.error("File is read-only"));
			}
			boolean wasDirty = editor.isDirty();
			ITextEditor textEditor = Adapters.adapt(editor, ITextEditor.class);
			IDocument document = document(textEditor);
			String before = document != null ? document.get() : null;
			formatWholeDocument(textEditor, document, target);
			boolean changed = document != null ? !document.get().equals(before) : editor.isDirty() && !wasDirty;
			if (changed && !wasDirty) {
				editor.doSave(new NullProgressMonitor());
			}
			return changed ? Outcome.CHANGED : Outcome.UNCHANGED;
		} finally {
			if (openedHere) {
				page.closeEditor(editor, false);
			}
		}
	}

	/**
	 * Without a selection some editors (e.g. the WTP XML/HTML editors) only format the empty region at the caret, so
	 * select the whole document first, like "Select All" + "Format" would. The previous selection is restored.
	 */
	private static void formatWholeDocument(ITextEditor editor, IDocument document, ITextOperationTarget target) {
		if (editor == null || document == null) {
			target.doOperation(ISourceViewer.FORMAT);
			return;
		}
		ISelectionProvider selectionProvider = editor.getSelectionProvider();
		ISelection previous = selectionProvider.getSelection();
		selectionProvider.setSelection(new TextSelection(document, 0, document.getLength()));
		try {
			target.doOperation(ISourceViewer.FORMAT);
		} finally {
			if (previous instanceof ITextSelection text && text.getOffset() + text.getLength() <= document.getLength()) {
				selectionProvider.setSelection(previous);
			} else {
				selectionProvider.setSelection(new TextSelection(document, 0, 0));
			}
		}
	}

	private static IDocument document(ITextEditor editor) {
		return editor == null ? null : editor.getDocumentProvider().getDocument(editor.getEditorInput());
	}

	private static IWorkbenchPage page() throws CoreException {
		IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
		if (window == null && PlatformUI.getWorkbench().getWorkbenchWindowCount() > 0) {
			window = PlatformUI.getWorkbench().getWorkbenchWindows()[0];
		}
		if (window == null || window.getActivePage() == null) {
			throw new CoreException(Status.error("No workbench page to open an editor in"));
		}
		return window.getActivePage();
	}

	interface UiCall<T> {
		T call() throws CoreException;
	}

	private static <T> T syncExec(UiCall<T> call) throws CoreException {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<CoreException> failure = new AtomicReference<>();
		AtomicReference<RuntimeException> crash = new AtomicReference<>();
		PlatformUI.getWorkbench().getDisplay().syncExec(() -> {
			try {
				result.set(call.call());
			} catch (CoreException e) {
				failure.set(e);
			} catch (RuntimeException e) {
				crash.set(e);
			}
		});
		if (failure.get() != null) {
			throw failure.get();
		}
		if (crash.get() != null) {
			throw crash.get();
		}
		return result.get();
	}
}
