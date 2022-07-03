package decaf.driver.error;

import decaf.frontend.tree.Pos;

// Typer error

public class LambdaCaptureError extends DecafError {

    public LambdaCaptureError(Pos pos) {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "cannot assign value to captured variables in lambda expression";
    }
}
