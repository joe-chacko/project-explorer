This tool assists in understanding the relationships between bnd projects in a bnd workspace.

To invoke the tool, issue the following command:\
`./gradlew [properties] run --args="<name of project>"`\
where the optional properties could include:&mdash;
 - `-Dproject.root="<root of bnd workspace>"`\
   the default is `~/git/liberty/open-liberty/dev`
 - `-Declipse.workspace="<root of eclipse workspace>"`\
   the default is `~/git/liberty/eclipse` (actually computed from `project.root`)
