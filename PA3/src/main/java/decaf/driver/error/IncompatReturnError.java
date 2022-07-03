package decaf.driver.error;

import decaf.frontend.tree.Pos;

// Typer error

public class IncompatReturnError extends DecafError {

    public IncompatReturnError(Pos pos) {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "incompatible return types in blocked expression";
    }
}
