#!/bin/bash
# show the AST tree of the specified Groovy file in GUI
exec java -cp groovy.jar groovy.inspect.swingui.AstBrowser "$@"
