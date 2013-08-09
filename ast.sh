#!/bin/bash
# show the AST tree of the specified Groovy file in GUI
exec groovy -e 'groovy.inspect.swingui.AstBrowser.main(args)' "$@"
