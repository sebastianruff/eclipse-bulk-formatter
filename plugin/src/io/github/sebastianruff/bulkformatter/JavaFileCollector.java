package io.github.sebastianruff.bulkformatter;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Collects the source compilation units contained in a selection of Java elements.
 */
public final class JavaFileCollector {

	private JavaFileCollector() {
	}

	/**
	 * @param elements           selected objects (packages, source folders, projects, compilation units)
	 * @param includeSubpackages whether packages also contribute the compilation units of their subpackages
	 * @return the compilation units in selection order, without duplicates
	 */
	public static Set<ICompilationUnit> collect(Iterable<?> elements, boolean includeSubpackages) throws CoreException {
		Set<ICompilationUnit> units = new LinkedHashSet<>();
		for (Object element : elements) {
			IJavaElement javaElement = toJavaElement(element);
			if (javaElement == null) {
				continue;
			}
			switch (javaElement.getElementType()) {
			case IJavaElement.JAVA_PROJECT -> addProject((IJavaProject) javaElement, units);
			case IJavaElement.PACKAGE_FRAGMENT_ROOT -> addRoot((IPackageFragmentRoot) javaElement, units);
			case IJavaElement.PACKAGE_FRAGMENT -> addPackage((IPackageFragment) javaElement, includeSubpackages, units);
			case IJavaElement.COMPILATION_UNIT -> units.add((ICompilationUnit) javaElement);
			default -> {
				// not a container of source files
			}
			}
		}
		return units;
	}

	private static IJavaElement toJavaElement(Object element) throws CoreException {
		IJavaElement javaElement = Adapters.adapt(element, IJavaElement.class);
		if (javaElement != null) {
			return javaElement;
		}
		IProject project = Adapters.adapt(element, IProject.class);
		if (project != null && project.isOpen() && project.hasNature(JavaCore.NATURE_ID)) {
			return JavaCore.create(project);
		}
		return null;
	}

	private static void addProject(IJavaProject project, Set<ICompilationUnit> units) throws JavaModelException {
		for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
			// skip source folders that are merely referenced from other projects
			if (project.equals(root.getJavaProject())) {
				addRoot(root, units);
			}
		}
	}

	private static void addRoot(IPackageFragmentRoot root, Set<ICompilationUnit> units) throws JavaModelException {
		if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
			return;
		}
		for (IJavaElement child : root.getChildren()) {
			addCompilationUnits((IPackageFragment) child, units);
		}
	}

	private static void addPackage(IPackageFragment pkg, boolean includeSubpackages, Set<ICompilationUnit> units)
			throws JavaModelException {
		if (!includeSubpackages) {
			addCompilationUnits(pkg, units);
			return;
		}
		String prefix = pkg.getElementName() + '.';
		IPackageFragmentRoot root = (IPackageFragmentRoot) pkg.getParent();
		for (IJavaElement child : root.getChildren()) {
			IPackageFragment candidate = (IPackageFragment) child;
			if (pkg.isDefaultPackage() || candidate.equals(pkg) || candidate.getElementName().startsWith(prefix)) {
				addCompilationUnits(candidate, units);
			}
		}
	}

	private static void addCompilationUnits(IPackageFragment pkg, Set<ICompilationUnit> units)
			throws JavaModelException {
		if (pkg.getKind() == IPackageFragmentRoot.K_SOURCE) {
			for (ICompilationUnit unit : pkg.getCompilationUnits()) {
				units.add(unit);
			}
		}
	}
}
