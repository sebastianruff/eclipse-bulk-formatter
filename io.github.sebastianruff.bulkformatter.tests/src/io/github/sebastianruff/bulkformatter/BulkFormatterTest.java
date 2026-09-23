package io.github.sebastianruff.bulkformatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkFormatterTest {

	private static final String UGLY = "package %s; public class %s{int   x;void m( ){if(x>0){x=1;}}}";

	private IJavaProject project;
	private IPackageFragmentRoot src;

	@BeforeEach
	void setUp() throws CoreException {
		IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject("demo");
		p.create(null);
		p.open(null);
		IProjectDescription description = p.getDescription();
		description.setNatureIds(new String[] { JavaCore.NATURE_ID });
		p.setDescription(description, null);
		IFolder folder = p.getFolder("src");
		folder.create(true, true, null);
		project = JavaCore.create(p);
		project.setRawClasspath(new IClasspathEntry[] { JavaCore.newSourceEntry(folder.getFullPath()) },
				p.getFullPath().append("bin"), null);
		src = project.getPackageFragmentRoot(folder);

		createClass("com.acme", "A");
		createClass("com.acme", "B");
		createClass("com.acme.sub", "C");
		createClass("com.acmeother", "D");
		createClass("com.acme", "Locked");
	}

	@AfterEach
	void tearDown() throws CoreException {
		IFile locked = project.getProject().getFile("src/com/acme/Locked.java");
		ResourceAttributes attributes = locked.getResourceAttributes();
		attributes.setReadOnly(false);
		locked.setResourceAttributes(attributes);
		project.getProject().delete(true, true, null);
	}

	@Test
	void collectsOnlyTheSelectedPackage() throws CoreException {
		Set<ICompilationUnit> units = JavaFileCollector.collect(List.of(pkg("com.acme")), false);
		assertEquals(Set.of("A.java", "B.java", "Locked.java"), names(units));
	}

	@Test
	void collectsSubpackagesButNotPackagesWithSamePrefix() throws CoreException {
		Set<ICompilationUnit> units = JavaFileCollector.collect(List.of(pkg("com.acme")), true);
		assertEquals(Set.of("A.java", "B.java", "Locked.java", "C.java"), names(units));
	}

	@Test
	void collectsWholeProject() throws CoreException {
		Set<ICompilationUnit> units = JavaFileCollector.collect(List.of(project.getProject()), false);
		assertEquals(Set.of("A.java", "B.java", "Locked.java", "C.java", "D.java"), names(units));
	}

	@Test
	void formatsFilesAndReportsReadOnlyOnes() throws Exception {
		Set<ICompilationUnit> units = JavaFileCollector.collect(List.of(pkg("com.acme")), false);
		setReadOnly("com/acme/Locked.java");

		BulkFormatter.Result result = BulkFormatter.format(units, new NullProgressMonitor());

		assertEquals(Set.of("A.java", "B.java"), names(result.changed));
		assertEquals(Set.of("Locked.java"), names(result.failed.keySet()));
		String formatted = read("com/acme/A.java");
		assertTrue(formatted.contains("\tint x;"), formatted);
		assertTrue(formatted.contains("\t\tif (x > 0) {"), formatted);
		assertEquals(String.format(UGLY, "com.acme", "Locked"), read("com/acme/Locked.java"));
		assertEquals(String.format(UGLY, "com.acme.sub", "C"), read("com/acme/sub/C.java"));

		// a second run finds nothing left to do
		BulkFormatter.Result again = BulkFormatter.format(Set.of(unit("com.acme", "A")), new NullProgressMonitor());
		assertEquals(1, again.unchanged.size());
	}

	private void setReadOnly(String path) throws CoreException {
		IFile file = project.getProject().getFile("src/" + path);
		ResourceAttributes attributes = file.getResourceAttributes();
		attributes.setReadOnly(true);
		file.setResourceAttributes(attributes);
	}

	private void createClass(String pkg, String name) throws CoreException {
		IPackageFragment fragment = src.createPackageFragment(pkg, true, null);
		fragment.createCompilationUnit(name + ".java", String.format(UGLY, pkg, name), true, null);
	}

	private IPackageFragment pkg(String name) {
		return src.getPackageFragment(name);
	}

	private ICompilationUnit unit(String pkg, String name) {
		return pkg(pkg).getCompilationUnit(name + ".java");
	}

	private String read(String path) throws Exception {
		IFile file = project.getProject().getFile("src/" + path);
		try (var in = file.getContents()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private static Set<String> names(java.util.Collection<ICompilationUnit> units) {
		return units.stream().map(ICompilationUnit::getElementName).collect(Collectors.toSet());
	}
}
