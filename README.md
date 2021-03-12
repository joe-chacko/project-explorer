This tool assists in understanding the relationships between bnd projects in a bnd workspace.

To build the tool, issue the following command:\
`./gradlew installDist`\

This will create an installable directory structure under `build/install`.

Either install this somewhere, or put the executable script under the directory into your path.

For example, I would use `ln -s $PWD/build/install/bnddeps/bin/bnddeps ~/bin`.

Note that the script cannot be moved or copied on its own, as it needs the rest of the directory structure to find the Java classes.