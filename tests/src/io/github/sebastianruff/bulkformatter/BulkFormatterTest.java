package io.github.sebastianruff.bulkformatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BulkFormatterTest {

	private static final String UGLY_JAVA = "package com.acme; public class %s{int   x;void m( ){if(x>0){x=1;}}}";

	private TestProject project;

	@BeforeEach
	void setUp() throws CoreException {
		project = new TestProject("format");
	}

	@AfterEach
	void tearDown() throws CoreException {
		project.delete();
	}

	@Test
	void formatsEachFileTypeWithItsFormatterAndSkipsTheRest() throws Exception {
		IFile java = project.create("src/com/acme/A.java", String.format(UGLY_JAVA, "A"));
		IFile editor = project.create("src/com/acme/notes.edtest", "via editor");
		IFile lsp = project.create("src/com/acme/data.lsptest", "via language server   \nline two\t\n");
		IFile text = project.create("src/com/acme/readme.txt", "plain text   ");
		IFile binary = project.create("src/com/acme/logo.png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 1, 2 });

		BulkFormatter.Result result = TestProject.format(Set.of(java, editor, lsp, text, binary));

		assertEquals(Set.of(), result.failed.keySet(), () -> result.failed.toString());
		assertEquals(names(java, editor, lsp), names(result.changed));
		assertEquals(names(text, binary), names(result.skipped));
		String formattedJava = project.read("src/com/acme/A.java");
		assertTrue(formattedJava.contains("\t\tif (x > 0) {"), formattedJava);
		assertEquals("VIA EDITOR", project.read("src/com/acme/notes.edtest"));
		assertEquals("via language server\nline two\n", project.read("src/com/acme/data.lsptest"));
		assertEquals("plain text   ", project.read("src/com/acme/readme.txt"));

		BulkFormatter.Result again = TestProject.format(Set.of(java, editor, lsp));
		assertEquals(names(java, editor, lsp), names(again.unchanged));
	}

	@Test
	void reportsReadOnlyFilesAndLeavesThemUntouched() throws Exception {
		IFile locked = project.create("src/com/acme/Locked.java", String.format(UGLY_JAVA, "Locked"));
		IFile lockedEditor = project.create("src/com/acme/locked.edtest", "locked");
		project.setReadOnly("src/com/acme/Locked.java", true);
		project.setReadOnly("src/com/acme/locked.edtest", true);
		try {
			BulkFormatter.Result result = TestProject.format(Set.of(locked, lockedEditor));

			assertEquals(names(locked, lockedEditor), names(result.failed.keySet()));
			assertEquals(String.format(UGLY_JAVA, "Locked"), project.read("src/com/acme/Locked.java"));
			assertEquals("locked", project.read("src/com/acme/locked.edtest"));
		} finally {
			project.setReadOnly("src/com/acme/Locked.java", false);
			project.setReadOnly("src/com/acme/locked.edtest", false);
		}
	}

	private static Set<String> names(IFile... files) {
		return names(Set.of(files));
	}

	private static Set<String> names(java.util.Collection<IFile> files) {
		return files.stream().map(IFile::getName).collect(Collectors.toSet());
	}
}
