package io.github.sebastianruff.bulkformatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
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
	private static final String SUBPACKAGES_LABEL = "Format (incl. Subpackages)";

	private IJavaProject project;
	private IPackageFragmentRoot src;

	@BeforeEach
	void setUp() throws CoreException {
		IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject("menu");
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
		for (String[] cls : new String[][] { { "com.acme", "A" }, { "com.acme.sub", "C" } }) {
			src.createPackageFragment(cls[0], true, null).createCompilationUnit(cls[1] + ".java",
					String.format(UGLY, cls[0], cls[1]), true, null);
		}
	}

	@AfterEach
	void tearDown() throws CoreException {
		project.getProject().delete(true, true, null);
	}

	@Test
	void subpackagesEntryIsInSourceMenuAndFormatsSubpackages() throws Exception {
		Menu contextMenu = openContextMenuOn(src.getPackageFragment("com.acme"));

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

		entry.notifyListeners(SWT.Selection, new Event());
		waitForJobs();
		assertTrue(read("com/acme/sub/C.java").contains("\tint x;"), read("com/acme/sub/C.java"));
		assertTrue(read("com/acme/A.java").contains("\tint x;"), read("com/acme/A.java"));
	}

	@Test
	void subpackagesEntryIsHiddenForSourceFolders() throws Exception {
		Menu contextMenu = openContextMenuOn(src);
		MenuItem source = find(contextMenu, "Source");
		assertNotNull(source, "Source submenu missing: " + labels(contextMenu));
		show(source.getMenu());
		assertNull(find(source.getMenu(), SUBPACKAGES_LABEL), labels(source.getMenu()));
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

	private String read(String path) throws Exception {
		IFile file = project.getProject().getFile("src/" + path);
		try (var in = file.getContents()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
