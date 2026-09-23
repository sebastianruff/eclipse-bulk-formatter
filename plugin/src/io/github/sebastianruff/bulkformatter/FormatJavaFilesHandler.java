package io.github.sebastianruff.bulkformatter;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.MultiRule;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * Source menu handler: formats all Java files of the selected packages and their subpackages. Runs without any
 * confirmation; progress is shown in the status bar. Errors are shown in a dialog and written to the Error Log.
 */
public class FormatJavaFilesHandler extends AbstractHandler {

	private static final String PLUGIN_ID = "io.github.sebastianruff.bulkformatter";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);

		Set<ICompilationUnit> units;
		try {
			units = JavaFileCollector.collect(selection.toList(), true);
		} catch (CoreException e) {
			throw new ExecutionException("Could not collect Java files", e);
		}
		if (units.isEmpty()) {
			return null;
		}

		// Let team providers check out files (only prompts if a team provider requires it).
		IFile[] files = units.stream().map(ICompilationUnit::getResource).filter(IFile.class::isInstance)
				.map(IFile.class::cast).toArray(IFile[]::new);
		IStatus editStatus = ResourcesPlugin.getWorkspace().validateEdit(files, HandlerUtil.getActiveShell(event));
		if (!editStatus.isOK()) {
			showAndLog(editStatus);
			return null;
		}

		createJob(units).schedule();
		return null;
	}

	private static WorkspaceJob createJob(Set<ICompilationUnit> units) {
		WorkspaceJob job = new WorkspaceJob("Format (incl. Subpackages)") {
			@Override
			public IStatus runInWorkspace(IProgressMonitor monitor) {
				try {
					reportFailures(BulkFormatter.format(units, monitor));
					return Status.OK_STATUS;
				} catch (OperationCanceledException e) {
					return Status.CANCEL_STATUS;
				}
			}
		};
		Set<IProject> projects = new LinkedHashSet<>();
		units.forEach(unit -> projects.add(unit.getJavaProject().getProject()));
		job.setRule(MultiRule.combine(projects.toArray(ISchedulingRule[]::new)));
		return job;
	}

	private static void reportFailures(BulkFormatter.Result result) {
		if (result.failed.isEmpty()) {
			return;
		}
		MultiStatus status = new MultiStatus(PLUGIN_ID, 0,
				NLS.bind("{0} of {1} Java file(s) could not be formatted", result.failed.size(), result.total()));
		result.failed.forEach((unit, message) -> status
				.add(Status.warning(unit.getPath() + ": " + message)));
		showAndLog(status);
	}

	private static void showAndLog(IStatus status) {
		StatusManager.getManager().handle(status, StatusManager.SHOW | StatusManager.LOG);
	}
}
