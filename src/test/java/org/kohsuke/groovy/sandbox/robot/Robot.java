package org.kohsuke.groovy.sandbox.robot;

/**
 * Robot that's exposed to a sandboxed script.
 *
 * Script can access all aspects of the robot except the brain, which contains a secret.
 *
 * @author Kohsuke Kawaguchi
 */
public class Robot {
    public class Arm {
        public void wave(int n) {
            // wave arms N times
        }
    }

    public void move() {}

    public class Leg {}

    // scripts will not have access to Brain
    public class Brain {}

    public final Brain brain = new Brain();

    public final Arm leftArm = new Arm(),rightArm = new Arm();
    public final Leg leftLeg = new Leg(),rightLeg = new Leg();
}
