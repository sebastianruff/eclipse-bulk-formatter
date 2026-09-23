package io.github.sebastianruff.bulkformatter;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.formatter.IContentFormatter;
import org.eclipse.jface.text.formatter.IFormattingStrategy;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.text.source.SourceViewerConfiguration;
import org.eclipse.ui.editors.text.TextEditor;

/** Test editor whose "formatter" upper-cases the document. */
public class UppercaseTestEditor extends TextEditor {

	public UppercaseTestEditor() {
		setSourceViewerConfiguration(new SourceViewerConfiguration() {
			@Override
			public IContentFormatter getContentFormatter(ISourceViewer sourceViewer) {
				return new IContentFormatter() {
					@Override
					public void format(IDocument document, IRegion region) {
						try {
							document.replace(0, document.getLength(), document.get().toUpperCase());
						} catch (BadLocationException e) {
							throw new IllegalStateException(e);
						}
					}

					@Override
					public IFormattingStrategy getFormattingStrategy(String contentType) {
						return null;
					}
				};
			}
		});
	}
}
