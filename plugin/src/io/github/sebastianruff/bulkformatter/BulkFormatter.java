package io.github.sebastianruff.bulkformatter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.TextEdit;

/**
 * Formats compilation units with the formatter settings of their project (project specific settings or workspace
 * defaults), exactly like Source &gt; Format does for a single file.
 */
public final class BulkFormatter {

	private static final String MODULE_INFO = "module-info.java";

	/** Outcome of a bulk format run. */
	public static final class Result {
		public final List<ICompilationUnit> changed = new ArrayList<>();
		public final List<ICompilationUnit> unchanged = new ArrayList<>();
		public final Map<ICompilationUnit, String> failed = new LinkedHashMap<>();

		public int total() {
			return changed.size() + unchanged.size() + failed.size();
		}
	}

	private BulkFormatter() {
	}

	/**
	 * Formats the given compilation units. Files that are open in a dirty editor are formatted in the editor but not
	 * saved, so unsaved user changes are never written to disk implicitly.
	 *
	 * @throws org.eclipse.core.runtime.OperationCanceledException if the monitor is canceled
	 */
	public static Result format(Collection<ICompilationUnit> units, IProgressMonitor monitor) {
		SubMonitor progress = SubMonitor.convert(monitor, "Formatting Java files", units.size());
		Map<IJavaProject, CodeFormatter> formatters = new HashMap<>();
		Result result = new Result();
		for (ICompilationUnit unit : units) {
			progress.subTask(unit.getElementName());
			CodeFormatter formatter = formatters.computeIfAbsent(unit.getJavaProject(),
					project -> ToolFactory.createCodeFormatter(project.getOptions(true)));
			try {
				if (formatUnit(unit, formatter, progress.split(1))) {
					result.changed.add(unit);
				} else {
					result.unchanged.add(unit);
				}
			} catch (CoreException | MalformedTreeException | BadLocationException e) {
				result.failed.put(unit, e.getMessage());
			}
		}
		return result;
	}

	private static boolean formatUnit(ICompilationUnit unit, CodeFormatter formatter, IProgressMonitor monitor)
			throws CoreException, BadLocationException {
		SubMonitor progress = SubMonitor.convert(monitor, 3);
		if (unit.isReadOnly()) {
			throw new CoreException(Status.error("File is read-only"));
		}
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
				return false;
			}
			unit.applyTextEdit(edit, progress.split(1));
			if (!dirtyInEditor) {
				unit.commitWorkingCopy(false, progress.split(1));
			}
			return true;
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
