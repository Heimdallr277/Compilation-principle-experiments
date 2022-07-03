package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：func args type must be non-void type<br>
 * PA2
 */
public class BadFunArgError extends DecafError {

    public BadFunArgError(Pos pos) {
        super(pos);
    }

    @Override
    protected String getErrMsg() {
        return "arguments in function type must be non-void known type";
    }

}
