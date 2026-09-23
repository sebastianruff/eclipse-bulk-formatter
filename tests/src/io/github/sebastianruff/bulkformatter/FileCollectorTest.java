package io.github.sebastianruff.bulkformatter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileCollectorTest {

	private TestProject project;

	@BeforeEach
	void setUp() throws CoreException {
		project = new TestProject("collect");
		project.create("src/com/acme/A.java", "package com.acme; class A {}");
		project.create("src/com/acme/messages.properties", "a=b");
		project.create("src/com/acme/sub/C.java", "package com.acme.sub; class C {}");
		project.create("src/com/acme/sub/layout.xml", "<a/>");
		project.create("src/com/acmeother/D.java", "package com.acmeother; class D {}");
		project.create("web/index.html", "<p>");
		project.create("web/node_modules/lib/x.js", "x");
		project.create("web/.hidden/y.json", "{}");
		project.create("bin/com/acme/A.class", new byte[] { 1 });
		project.create("generated/G.java", "class G {}").getParent().setDerived(true, null);
	}

	@AfterEach
	void tearDown() throws CoreException {
		project.delete();
	}

	@Test
	void packageIncludesSubpackagesAndAllFileTypesButNotPackagesWithSamePrefix() throws CoreException {
		assertEquals(Set.of("src/com/acme/A.java", "src/com/acme/messages.properties", "src/com/acme/sub/C.java",
				"src/com/acme/sub/layout.xml"), collect(project.src.getPackageFragment("com.acme")));
	}

	@Test
	void folderIncludesSubfolders() throws CoreException {
		assertEquals(Set.of("web/index.html"), collect(project.project().getFolder("web")));
	}

	@Test
	void projectSkipsMetadataOutputDerivedAndNodeModules() throws CoreException {
		assertEquals(Set.of("src/com/acme/A.java", "src/com/acme/messages.properties", "src/com/acme/sub/C.java",
				"src/com/acme/sub/layout.xml", "src/com/acmeother/D.java", "web/index.html"),
				collect(project.javaProject));
	}

	private static Set<String> collect(Object element) throws CoreException {
		return FileCollector.collect(List.of(element)).stream()
				.map(IFile::getProjectRelativePath).map(Object::toString).collect(Collectors.toSet());
	}
}
