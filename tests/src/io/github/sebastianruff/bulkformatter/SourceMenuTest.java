package io.github.sebastianruff.bulkformatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.ui.IPackagesViewPart;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Opens the real Package Explorer context menu and checks where the entries show up. */
class SourceMenuTest {

	private static final String UGLY = "package %s; public class %s{int   x;}";
	private static final String FOLDER_LABEL = "Deep Format";
	private static final String SUBPACKAGES_LABEL = "Deep Format (incl. Subpackages)";

	private TestProject project;

	@BeforeEach
	void setUp() throws CoreException {
		project = new TestProject("menu");
		project.create("src/com/acme/A.java", String.format(UGLY, "com.acme", "A"));
		project.create("src/com/acme/sub/C.java", String.format(UGLY, "com.acme.sub", "C"));
		project.create("src/com/acme/sub/notes.edtest", "sub");
		project.create("web/page.edtest", "page");
	}

	@AfterEach
	void tearDown() throws CoreException {
		project.delete();
	}

	@Test
	void subpackagesEntryIsInSourceMenuAndFormatsSubpackages() throws Exception {
		Menu contextMenu = openContextMenuOn(project.src.getPackageFragment("com.acme"));

		MenuItem source = find(contextMenu, "Source");
		assertNotNull(source, "Source submenu missing: " + labels(contextMenu));
		assertNull(find(contextMenu, SUBPACKAGES_LABEL), "entry must not be in the top level menu");

		Menu sourceMenu = source.getMenu();
		show(sourceMenu);
		MenuItem entry = find(sourceMenu, SUBPACKAGES_LABEL);
		assertNotNull(entry, "entry missing in Source submenu: " + labels(sourceMenu));
		String[] items = labels(sourceMenu).split("\\|");
		int formatIndex = java.util.Arrays.asList(items).indexOf("Format");
		assertEquals(SUBPACKAGES_LABEL, items[formatIndex + 1], "entry should follow Format: " + labels(sourceMenu));
		assertNotNull(entry.getImage(), "entry should have an icon");

		entry.notifyListeners(SWT.Selection, new Event());
		waitForJobs();
		assertTrue(project.read("src/com/acme/sub/C.java").contains("\tint x;"), project.read("src/com/acme/sub/C.java"));
		assertTrue(project.read("src/com/acme/A.java").contains("\tint x;"), project.read("src/com/acme/A.java"));
		assertEquals("SUB", project.read("src/com/acme/sub/notes.edtest"));
	}

	@Test
	void folderEntryFormatsPlainFolders() throws Exception {
		Menu contextMenu = openContextMenuOn(project.project().getFolder("web"));
		MenuItem entry = find(contextMenu, FOLDER_LABEL);
		assertNotNull(entry, "entry missing: " + labels(contextMenu));
		assertNotNull(entry.getImage(), "entry should have an icon");
		entry.notifyListeners(SWT.Selection, new Event());
		waitForJobs();
		assertEquals("PAGE", project.read("web/page.edtest"));
	}

	@Test
	void sourceFolderHasDeepFormatRightAfterFormat() throws Exception {
		Menu contextMenu = openContextMenuOn(project.src);
		MenuItem source = find(contextMenu, "Source");
		assertNotNull(source, "Source submenu missing: " + labels(contextMenu));
		show(source.getMenu());
		String[] items = labels(source.getMenu()).split("\\|");
		int formatIndex = java.util.Arrays.asList(items).indexOf("Format");
		assertEquals(FOLDER_LABEL, items[formatIndex + 1], "entry should follow Format: " + labels(source.getMenu()));
		assertNull(find(source.getMenu(), SUBPACKAGES_LABEL), labels(source.getMenu()));
		assertNotNull(find(contextMenu, FOLDER_LABEL), labels(contextMenu));
	}

	private Menu openContextMenuOn(Object element) throws Exception {
		IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
		IPackagesViewPart explorer = (IPackagesViewPart) page.showView(JavaUI.ID_PACKAGES);
		explorer.getTreeViewer().refresh();
		explorer.selectAndReveal(element);
		processEvents();
		Menu menu = explorer.getTreeViewer().getTree().getMenu();
		show(menu);
		return menu;
	}

	private static void show(Menu menu) {
		menu.notifyListeners(SWT.Show, new Event());
		processEvents();
	}

	private static MenuItem find(Menu menu, String label) {
		for (MenuItem item : menu.getItems()) {
			if (label.equals(clean(item.getText()))) {
				return item;
			}
		}
		return null;
	}

	private static String labels(Menu menu) {
		StringBuilder sb = new StringBuilder();
		for (MenuItem item : menu.getItems()) {
			if ((item.getStyle() & SWT.SEPARATOR) == 0) {
				sb.append(sb.length() > 0 ? "|" : "").append(clean(item.getText()));
			}
		}
		return sb.toString();
	}

	private static String clean(String text) {
		int tab = text.indexOf('\t'); // strip key binding
		return (tab >= 0 ? text.substring(0, tab) : text).replace("&", "");
	}

	private static void processEvents() {
		Display display = Display.getCurrent();
		while (display.readAndDispatch()) {
			// drain
		}
	}

	private static void waitForJobs() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 20_000;
		while (!Job.getJobManager().isIdle() && System.currentTimeMillis() < deadline) {
			processEvents();
			Thread.sleep(20);
		}
		processEvents();
	}
}
