package decaf.driver.error;

import decaf.frontend.tree.Pos;

/**
 * example：cannot instantiate abstract class 'Foo'<br>
 * PA2
 */
public class InstantiateAbsClassError extends DecafError {

    private String name;

    public InstantiateAbsClassError(Pos pos, String name) {
        super(pos);
        this.name = name;
    }

    @Override
    protected String getErrMsg() {
        return "cannot instantiate abstract class '" + name + "'";
    }

}
