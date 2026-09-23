package io.github.sebastianruff.bulkformatter;

/** What happened to a single file. */
enum Outcome {
	/** The file was reformatted. */
	CHANGED,
	/** A formatter ran but the file was already formatted. */
	UNCHANGED,
	/** No formatter is available for this kind of file. */
	NOT_SUPPORTED
}
