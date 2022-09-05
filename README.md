Purpose
-------
Use this tool to find dependent open-liberty projects in the bnd workspace,
and understand which projects are already imported into an eclipse workspace.

## Installing and running the tool
Clone this git repository locally.

Run the `px` script that is in the same directory as this README.
Add this location to your PATH or symlink the script into an existing path location.

Invoke `px help` for syntax help.

## Configuration
Command line options can be specified in a settings file in the user's home directory called `.px.properties`.  
In particular, consider setting the following properties:
```properties
px.bnd-workspace=/path/to/bnd/workspace
px.eclipse-workspace=/path/to/eclipse/workspace
```