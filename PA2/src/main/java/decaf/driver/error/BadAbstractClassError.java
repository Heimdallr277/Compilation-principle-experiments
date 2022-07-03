package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：'Foo' is not abstract and does not override all abstract methods<br>
 * PA2
 */
public class BadAbstractClassError extends DecafError {

    private String name;

    public BadAbstractClassError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return "'" + name + "' is not abstract and does not override all abstract methods";
    }

}
