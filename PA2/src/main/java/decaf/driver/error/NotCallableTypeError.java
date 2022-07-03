package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * string is not a class type.
 */
public class NotCallableTypeError extends DecafError {

    private String type;

    public NotCallableTypeError(String type, Pos pos) {
        super(pos);
        this.type = type;
    }

    @Override
    protected String getErrMsg() {
        return type + " is not a callable type";
    }

}
