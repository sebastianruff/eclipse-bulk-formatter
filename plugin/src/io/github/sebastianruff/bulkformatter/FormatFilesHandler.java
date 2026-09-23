package io.github.sebastianruff.bulkformatter;

import java.util.Set;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.statushandlers.StatusManager;

/**
 * Formats all files of the selected packages, folders or projects including their subpackages and subfolders. Runs
 * without any confirmation; progress is shown in the status bar. Errors are shown in a dialog and written to the
 * Error Log.
 */
public class FormatFilesHandler extends AbstractHandler {

	private static final String PLUGIN_ID = "io.github.sebastianruff.bulkformatter";

	@Override
	public Object execute(ExecutionEvent event) throws ExecutionException {
		IStructuredSelection selection = HandlerUtil.getCurrentStructuredSelection(event);

		Set<IFile> files;
		try {
			files = FileCollector.collect(selection.toList());
		} catch (CoreException e) {
			throw new ExecutionException("Could not collect files", e);
		}
		if (files.isEmpty()) {
			return null;
		}

		// Let team providers check out files (only prompts if a team provider requires it).
		IStatus editStatus = ResourcesPlugin.getWorkspace().validateEdit(files.toArray(IFile[]::new),
				HandlerUtil.getActiveShell(event));
		if (!editStatus.isOK()) {
			showAndLog(editStatus);
			return null;
		}

		createJob(files).schedule();
		return null;
	}

	/**
	 * Deliberately a plain job without scheduling rule: formatting via editors happens in the UI thread, which must be
	 * able to save files while this job is running.
	 */
	private static Job createJob(Set<IFile> files) {
		Job job = Job.create("Format (incl. Subfolders)", (IProgressMonitor monitor) -> {
			try {
				reportFailures(BulkFormatter.format(files, monitor));
				return Status.OK_STATUS;
			} catch (OperationCanceledException e) {
				return Status.CANCEL_STATUS;
			}
		});
		return job;
	}

	private static void reportFailures(BulkFormatter.Result result) {
		if (result.failed.isEmpty()) {
			return;
		}
		MultiStatus status = new MultiStatus(PLUGIN_ID, 0,
				NLS.bind("{0} of {1} file(s) could not be formatted", result.failed.size(), result.total()));
		result.failed.forEach((file, message) -> status.add(Status.warning(file.getFullPath() + ": " + message)));
		showAndLog(status);
	}

	private static void showAndLog(IStatus status) {
		StatusManager.getManager().handle(status, StatusManager.SHOW | StatusManager.LOG);
	}
}
