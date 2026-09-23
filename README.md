<img src="marketplace/logo.png" width="96" align="right" alt="">

# Format incl. Subpackages for Eclipse

[![Build](https://github.com/sebastianruff/eclipse-bulk-formatter/actions/workflows/build.yml/badge.svg)](https://github.com/sebastianruff/eclipse-bulk-formatter/actions/workflows/build.yml)

Eclipse's built-in *Source > Format* on a package only formats the files directly inside that package,
not its subpackages. This plugin adds **Source > Format (incl. Subpackages)** to the context menu of
packages in the Package Explorer: it formats the selected packages and all of their subpackages
(within the same source folder).

- Uses the project's formatter settings (project-specific settings or workspace default), just like *Source > Format*.
- Runs immediately in the background without confirmation; only errors are shown.
- Files that are already formatted are left untouched.
- Files open in an editor with unsaved changes are formatted in the editor but not saved.

## Install

In Eclipse, choose *Help > Install New Software…* and use this update site:

    https://sebastianruff.github.io/eclipse-bulk-formatter/

Select “Format incl. Subpackages”, finish the wizard and restart Eclipse.
The update site is PGP-signed. When Eclipse asks whether to trust the signing key, check that the
fingerprint is `8FEA 6CD6 3AF8 D180 5DC8  9737 081E E7BE B9AC A451` ([signing-key.asc](signing-key.asc)).

Requires Eclipse running on Java 21 or newer (built and tested against Eclipse 2026-06).

## Build

Requires Maven 3.9+ and JDK 21+.

    mvn clean verify

The update site is created in `site/target/repository`
(zipped: `site/target/*.zip`).
Every push to `main` builds, tests and publishes the update site to GitHub Pages.

## Development

Import all projects via *File > Import > Existing Projects* into an Eclipse with PDE
(“Eclipse IDE for RCP and RAP Developers”) and launch *Run As > Eclipse Application*.

## License

[Eclipse Public License 2.0](LICENSE)
