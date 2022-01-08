package nl.bassages.np30.domain;

public class Np30ControllerUncheckedException extends RuntimeException {
    public Np30ControllerUncheckedException(Throwable cause) {
        super(cause);
    }

    public Np30ControllerUncheckedException(String message) {
        super(message);
    }
}
