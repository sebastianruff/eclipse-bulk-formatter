package io.github.sebastianruff.bulkformatter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** In-process language server whose formatting removes trailing whitespace. */
public class TestLanguageServer implements LanguageServer, TextDocumentService, WorkspaceService {

	private final Map<String, String> documents = new ConcurrentHashMap<>();

	static String format(String text) {
		return text.replaceAll("(?m)[ \\t]+$", "");
	}

	@Override
	public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
		ServerCapabilities capabilities = new ServerCapabilities();
		capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
		capabilities.setDocumentFormattingProvider(true);
		return CompletableFuture.completedFuture(new InitializeResult(capabilities));
	}

	@Override
	public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
		String text = documents.get(params.getTextDocument().getUri());
		if (text == null || format(text).equals(text)) {
			return CompletableFuture.completedFuture(List.of());
		}
		String[] lines = text.split("\n", -1);
		Range all = new Range(new Position(0, 0), new Position(lines.length - 1, lines[lines.length - 1].length()));
		return CompletableFuture.completedFuture(List.of(new TextEdit(all, format(text))));
	}

	@Override
	public void didOpen(DidOpenTextDocumentParams params) {
		documents.put(params.getTextDocument().getUri(), params.getTextDocument().getText());
	}

	@Override
	public void didChange(DidChangeTextDocumentParams params) {
		documents.put(params.getTextDocument().getUri(), params.getContentChanges().get(0).getText());
	}

	@Override
	public void didClose(DidCloseTextDocumentParams params) {
		documents.remove(params.getTextDocument().getUri());
	}

	@Override
	public void didSave(DidSaveTextDocumentParams params) {
	}

	@Override
	public void didChangeConfiguration(DidChangeConfigurationParams params) {
	}

	@Override
	public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
	}

	@Override
	public CompletableFuture<Object> shutdown() {
		return CompletableFuture.completedFuture(null);
	}

	@Override
	public void exit() {
	}

	@Override
	public TextDocumentService getTextDocumentService() {
		return this;
	}

	@Override
	public WorkspaceService getWorkspaceService() {
		return this;
	}

	/** Connects LSP4E to a {@link TestLanguageServer} running in this JVM. */
	public static class Connection implements StreamConnectionProvider {
		private InputStream clientInput;
		private OutputStream clientOutput;

		@Override
		public void start() throws IOException {
			PipedInputStream serverInput = new PipedInputStream(1 << 16);
			clientOutput = new PipedOutputStream(serverInput);
			PipedInputStream clientIn = new PipedInputStream(1 << 16);
			OutputStream serverOutput = new PipedOutputStream(clientIn);
			clientInput = clientIn;
			LSPLauncher.createServerLauncher(new TestLanguageServer(), serverInput, serverOutput)
					.startListening();
		}

		@Override
		public InputStream getInputStream() {
			return clientInput;
		}

		@Override
		public OutputStream getOutputStream() {
			return clientOutput;
		}

		@Override
		public InputStream getErrorStream() {
			return null;
		}

		/** Closing the server's input ends its message loop cleanly. */
		@Override
		public void stop() {
			try {
				if (clientOutput != null) {
					clientOutput.close();
				}
			} catch (IOException e) {
				// already closed
			}
		}
	}
}
