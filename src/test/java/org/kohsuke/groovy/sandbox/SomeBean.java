package org.kohsuke.groovy.sandbox;

/**
 * For testing field and attribute access.
 *
 * @author Kohsuke Kawaguchi
 */
public class SomeBean {
    private int x;

    public SomeBean(int x, int y) {
        this.x = x;
        this.y = y;
    }

    int getX() {
        return x;
    }

    void setX(int x) {
        this.x = x;
    }

    public int y;
}
