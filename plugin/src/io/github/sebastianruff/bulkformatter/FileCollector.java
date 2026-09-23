package io.github.sebastianruff.bulkformatter;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceProxy;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaCore;

/**
 * Collects the files below the selected packages, folders and projects, including all subpackages and subfolders.
 * Skips resources nobody wants reformatted: derived, hidden and team-private resources, dot-files and dot-folders
 * (.git, .settings, .project, ...), node_modules and Java output folders.
 */
public final class FileCollector {

	private FileCollector() {
	}

	public static Set<IFile> collect(Iterable<?> elements) throws CoreException {
		Set<IFile> files = new LinkedHashSet<>();
		for (Object element : elements) {
			IResource resource = toResource(element);
			if (resource instanceof IFile file) {
				files.add(file);
			} else if (resource instanceof IContainer container && container.isAccessible()) {
				Set<IPath> outputFolders = outputFolders(container.getProject());
				container.accept(proxy -> {
					if (proxy.getType() != IResource.FILE && proxy.requestFullPath().equals(container.getFullPath())) {
						return true; // always descend into the selected container itself
					}
					if (isExcluded(proxy, outputFolders)) {
						return false;
					}
					if (proxy.getType() == IResource.FILE) {
						files.add((IFile) proxy.requestResource());
					}
					return true;
				}, IResource.NONE);
			}
		}
		return files;
	}

	private static IResource toResource(Object element) {
		if (element instanceof IPackageFragment pkg && pkg.isDefaultPackage()) {
			// the default package contains all other packages of its source folder
			return pkg.getParent().getResource();
		}
		if (element instanceof IJavaElement javaElement) {
			return javaElement.getResource();
		}
		return Adapters.adapt(element, IResource.class);
	}

	private static boolean isExcluded(IResourceProxy proxy, Set<IPath> outputFolders) {
		String name = proxy.getName();
		return proxy.isDerived() || proxy.isHidden() || proxy.isTeamPrivateMember() || name.startsWith(".")
				|| "node_modules".equals(name) || outputFolders.contains(proxy.requestFullPath());
	}

	private static Set<IPath> outputFolders(IProject project) throws CoreException {
		Set<IPath> folders = new HashSet<>();
		if (project == null || !project.hasNature(JavaCore.NATURE_ID)) {
			return folders;
		}
		IJavaProject javaProject = JavaCore.create(project);
		folders.add(javaProject.getOutputLocation());
		for (IClasspathEntry entry : javaProject.getRawClasspath()) {
			if (entry.getOutputLocation() != null) {
				folders.add(entry.getOutputLocation());
			}
		}
		return folders;
	}
}
