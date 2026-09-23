package io.github.sebastianruff.bulkformatter;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.ui.IPackagesViewPart;
import org.eclipse.jdt.ui.JavaUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeItem;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.IDE;
import org.junit.jupiter.api.Test;

/**
 * Not a test: creates the screenshots for the Marketplace listing. Run with {@code mvn verify -Pscreenshots}; the
 * images are written to {@code marketplace/screenshots}.
 */
class MarketplaceScreenshots {

	private static final File OUT = new File(System.getProperty("screenshots.dir", "target/screenshots"));

	private Display display;
	private Shell shell;

	@Test
	void capture() throws Exception {
		OUT.mkdirs();
		display = Display.getCurrent();
		TestProject project = createDemoProject();
		try {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			shell = window.getShell();
			if (PlatformUI.getWorkbench().getIntroManager().getIntro() != null) {
				PlatformUI.getWorkbench().getIntroManager().closeIntro(PlatformUI.getWorkbench().getIntroManager().getIntro());
			}
			PlatformUI.getWorkbench().showPerspective(JavaUI.ID_PERSPECTIVE, window);
			Rectangle screen = display.getPrimaryMonitor().getClientArea();
			shell.setBounds(screen.x + 40, screen.y + 20, Math.min(1400, screen.width - 80), Math.min(940, screen.height - 40));
			IWorkbenchPage page = window.getActivePage();
			IDE.openEditor(page, project.file("src/com/example/shop/OrderService.java"));
			IPackagesViewPart explorer = (IPackagesViewPart) page.showView(JavaUI.ID_PACKAGES);
			settle(1500);

			// 1: Format (incl. Subfolders) on a folder. (The Source submenu of packages can only be opened by real
			// mouse/keyboard input, which would need the Accessibility permission, so there is no shot of it.)
			IPackageFragment pkg = project.src.getPackageFragment("com.example.shop");
			explorer.getTreeViewer().expandToLevel(project.src, 1);
			explorer.selectAndReveal(project.project().getFolder("web"));
			bringToFront();
			showMenuAndCapture(explorer.getTreeViewer().getTree(), "folder-menu.png");

			// 2 + 3: an editor before and after formatting the whole project
			explorer.selectAndReveal(pkg);
			bringToFront();
			System.out.println("SCREENSHOT editor-before.png: " + captureInUi("editor-before.png"));
			java.util.Set<org.eclipse.core.resources.IFile> files = FileCollector.collect(java.util.List.of(project.javaProject));
			BulkFormatter.Result result = TestProject.format(files);
			System.out.println("STEP formatted " + result.changed.size() + " files, skipped " + result.skipped + ", failed " + result.failed);
			bringToFront();
			System.out.println("SCREENSHOT editor-after.png: " + captureInUi("editor-after.png"));
		} finally {
			project.delete();
		}
	}

	private void showMenuAndCapture(Tree tree, String name) throws Exception {
		TreeItem item = tree.getSelection()[0];
		Rectangle bounds = item.getBounds();
		Point location = tree.toDisplay(bounds.x + bounds.width / 2, bounds.y + bounds.height);
		Menu menu = tree.getMenu();
		menu.setLocation(location);
		AtomicReference<String> result = new AtomicReference<>();
		Thread photographer = new Thread(() -> {
			try {
				sleep(1500);
				result.set(capture(name));
			} catch (Exception e) {
				result.set("error: " + e);
			} finally {
				display.asyncExec(() -> menu.setVisible(false));
			}
		});
		photographer.start();
		menu.setVisible(true);
		long deadline = System.currentTimeMillis() + 20_000;
		while (photographer.isAlive() && System.currentTimeMillis() < deadline) {
			if (!display.readAndDispatch()) {
				Thread.sleep(10);
			}
		}
		menu.setVisible(false);
		settle(500);
		System.out.println("SCREENSHOT " + name + ": " + result.get());
	}

	private void bringToFront() {
		shell.forceActive();
		shell.forceFocus();
		settle(1500);
	}

	/** macOS window number of the workbench window (via reflection: SWT's cocoa classes don't exist on Linux CI). */
	private long windowNumber() throws Exception {
		AtomicReference<Object> view = new AtomicReference<>();
		display.syncExec(() -> {
			try {
				view.set(org.eclipse.swt.widgets.Control.class.getField("view").get(shell));
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		Object window = view.get().getClass().getMethod("window").invoke(view.get());
		return ((Number) window.getClass().getMethod("windowNumber").invoke(window)).longValue();
	}

	/** Captures only the workbench window itself, so no other application can end up in the image. */
	private String captureInUi(String name) throws Exception {
		long id = windowNumber();
		AtomicReference<String> result = new AtomicReference<>();
		Thread t = new Thread(() -> {
			try {
				File file = new File(OUT, name);
				Process p = new ProcessBuilder("screencapture", "-x", "-o", "-l" + id, file.getAbsolutePath())
						.redirectErrorStream(true).start();
				String output = new String(p.getInputStream().readAllBytes()).trim();
				result.set(p.waitFor() == 0 ? "window " + id + " -> " + file : "failed: " + output);
			} catch (Exception e) {
				result.set("error: " + e);
			}
		});
		t.start();
		while (t.isAlive()) {
			if (!display.readAndDispatch()) {
				Thread.sleep(10);
			}
		}
		return result.get();
	}

	/** Captures the workbench window area (for menus, which are separate windows): Eclipse must be in front. */
	private String capture(String name) throws Exception {
		AtomicReference<Rectangle> area = new AtomicReference<>();
		display.syncExec(() -> area.set(shell.getBounds()));
		Rectangle r = area.get();
		File file = new File(OUT, name);
		Process p = new ProcessBuilder("screencapture", "-x", "-o", "-R" + r.x + "," + r.y + "," + r.width + "," + r.height,
				file.getAbsolutePath()).redirectErrorStream(true).start();
		String output = new String(p.getInputStream().readAllBytes()).trim();
		if (p.waitFor() == 0 && file.length() > 0) {
			return "screencapture -> " + file;
		}
		AtomicReference<String> swt = new AtomicReference<>();
		display.syncExec(() -> {
			Image image = new Image(display, r.width, r.height);
			GC gc = new GC(display);
			gc.copyArea(image, r.x, r.y);
			gc.dispose();
			ImageData data = image.getImageData();
			image.dispose();
			ImageLoader loader = new ImageLoader();
			loader.data = new ImageData[] { data };
			loader.save(file.getAbsolutePath(), SWT.IMAGE_PNG);
			swt.set("SWT copyArea -> " + file + " (screencapture said: " + output + ")");
		});
		return swt.get();
	}

	private void settle(long millis) {
		long end = System.currentTimeMillis() + millis;
		while (System.currentTimeMillis() < end) {
			if (!display.readAndDispatch()) {
				sleep(10);
			}
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static TestProject createDemoProject() throws Exception {
		TestProject p = new TestProject("demo-shop");
		IClasspathEntry[] entries = p.javaProject.getRawClasspath();
		IClasspathEntry[] withJre = java.util.Arrays.copyOf(entries, entries.length + 1);
		withJre[entries.length] = JavaCore.newContainerEntry(IPath.fromOSString("org.eclipse.jdt.launching.JRE_CONTAINER"));
		p.javaProject.setRawClasspath(withJre, null);
		p.create("src/com/example/shop/Order.java",
				"package com.example.shop;\npublic record Order(String id,int amount){}");
		p.create("src/com/example/shop/OrderService.java",
				"package com.example.shop;\nimport java.util.*;\npublic class OrderService{\nprivate final List<Order> orders=new ArrayList<>();\npublic void add(Order o){if(o!=null){orders.add(o);}}\npublic int total(){int sum=0;for(Order o:orders){sum+=o.amount();}return sum;}\n}");
		p.create("src/com/example/shop/orders.xml", "<orders><order id=\"1\"><amount>3</amount></order></orders>");
		p.create("src/com/example/shop/api/OrderController.java",
				"package com.example.shop.api;\npublic class OrderController{public String get(String id){return id;}}");
		p.create("src/com/example/shop/api/openapi.yaml", "openapi:   3.0.0\ninfo:\n    title:   Shop\n");
		p.create("src/com/example/shop/api/dto/OrderDto.java",
				"package com.example.shop.api.dto;\npublic record OrderDto(String id){}");
		p.create("src/com/example/shop/api/dto/example.json", "{\"id\":\"1\",\"amount\":3}");
		p.create("web/index.html", "<html><body><h1>Shop</h1></body></html>");
		p.create("web/css/style.css", "h1{color:#333;margin:0}");
		p.create("web/js/app.js", "function total(a,b){return a+b}");
		return p;
	}
}
