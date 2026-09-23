package io.github.sebastianruff.bulkformatter;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

/**
 * Formats Java source files headlessly with the formatter settings of their project (project specific settings or
 * workspace defaults), exactly like Source &gt; Format does.
 */
final class JavaStrategy {

	private static final String MODULE_INFO = "module-info.java";

	private final Map<IJavaProject, CodeFormatter> formatters = new HashMap<>();

	/** @return the compilation unit if the file is a Java source file on its project's build path, else null */
	static ICompilationUnit asCompilationUnit(IFile file) {
		IJavaElement element = JavaCore.create(file);
		if (element instanceof ICompilationUnit unit && unit.getJavaProject().isOnClasspath(unit)) {
			return unit;
		}
		return null;
	}

	/**
	 * Files that are open in a dirty editor are formatted in the editor but not saved, so unsaved user changes are
	 * never written to disk implicitly.
	 */
	Outcome format(ICompilationUnit unit, IProgressMonitor monitor) throws CoreException, BadLocationException {
		SubMonitor progress = SubMonitor.convert(monitor, 3);
		if (unit.isReadOnly()) {
			throw new CoreException(Status.error("File is read-only"));
		}
		CodeFormatter formatter = formatters.computeIfAbsent(unit.getJavaProject(),
				project -> ToolFactory.createCodeFormatter(project.getOptions(true)));
		boolean dirtyInEditor = unit.isWorkingCopy() && unit.hasUnsavedChanges();
		unit.becomeWorkingCopy(progress.split(1));
		try {
			String source = unit.getBuffer().getContents();
			TextEdit edit = formatter.format(formatKind(unit), source, 0, source.length(), 0,
					unit.findRecommendedLineSeparator());
			if (edit == null) {
				throw new CoreException(Status.error("The formatter could not process the file"));
			}
			if (!changesSource(source, edit)) {
				return Outcome.UNCHANGED;
			}
			unit.applyTextEdit(edit, progress.split(1));
			if (!dirtyInEditor) {
				unit.commitWorkingCopy(false, progress.split(1));
			}
			return Outcome.CHANGED;
		} finally {
			discard(unit);
		}
	}

	private static int formatKind(ICompilationUnit unit) {
		int kind = MODULE_INFO.equals(unit.getElementName()) ? CodeFormatter.K_MODULE_INFO
				: CodeFormatter.K_COMPILATION_UNIT;
		return kind | CodeFormatter.F_INCLUDE_COMMENTS;
	}

	/** The formatter may return edits that replace text with identical text; don't touch such files. */
	private static boolean changesSource(String source, TextEdit edit) throws BadLocationException {
		Document document = new Document(source);
		edit.copy().apply(document, TextEdit.NONE);
		return !document.get().equals(source);
	}

	private static void discard(ICompilationUnit unit) {
		try {
			unit.discardWorkingCopy();
		} catch (JavaModelException e) {
			// nothing sensible left to do
		}
	}
}
