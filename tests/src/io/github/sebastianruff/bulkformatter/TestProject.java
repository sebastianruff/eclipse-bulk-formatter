package io.github.sebastianruff.bulkformatter;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.swt.widgets.Display;

/** A Java project with a src folder, plus helpers to create files and run the formatter. */
final class TestProject {

	final IJavaProject javaProject;
	final IPackageFragmentRoot src;

	TestProject(String name) throws CoreException {
		IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(name);
		p.create(null);
		p.open(null);
		IProjectDescription description = p.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		p.setDescription(description, null);
		IFolder folder = p.getFolder("src");
		folder.create(true, true, null);
		javaProject = JavaCore.create(p);
		javaProject.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(folder.getFullPath()) },
				p.getFullPath().append("bin"), null);
		src = javaProject.getPackageFragmentRoot(folder);
	}

	IProject project() {
		return javaProject.getProject();
	}

	IFile file(String path) {
		return project().getFile(path);
	}

	IFile create(String path, String content) throws CoreException {
		return create(path, content.getBytes(StandardCharsets.UTF_8));
	}

	IFile create(String path, byte[] content) throws CoreException {
		IFile file = file(path);
		createParents(file.getParent());
		file.create(new ByteArrayInputStream(content), true, null);
		return file;
	}

	private static void createParents(IContainer container) throws CoreException {
		if (container instanceof IFolder folder && !folder.exists()) {
			createParents(folder.getParent());
			folder.create(true, true, null);
		}
	}

	String read(String path) throws Exception {
		try (var in = file(path).getContents()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	void setReadOnly(String path, boolean readOnly) throws CoreException {
		IFile file = file(path);
		ResourceAttributes attributes = file.getResourceAttributes();
		attributes.setReadOnly(readOnly);
		file.setResourceAttributes(attributes);
	}

	void delete() throws CoreException {
		project().delete(true, true, null);
	}

	/** Runs the formatter like the handler does: in a background job, while the UI thread keeps dispatching. */
	static BulkFormatter.Result format(Collection<IFile> files) {
		AtomicReference<BulkFormatter.Result> result = new AtomicReference<>();
		Job job = Job.create("format", monitor -> {
			result.set(BulkFormatter.format(files, monitor));
			return Status.OK_STATUS;
		});
		job.schedule();
		long deadline = System.currentTimeMillis() + 60_000;
		while (result.get() == null && System.currentTimeMillis() < deadline) {
			processEvents();
		}
		if (result.get() == null) {
			throw new AssertionError("formatter did not finish");
		}
		processEvents();
		return result.get();
	}

	static void processEvents() {
		Display display = Display.getCurrent();
		if (!display.readAndDispatch()) {
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		while (display.readAndDispatch()) {
			// drain
		}
	}
}
