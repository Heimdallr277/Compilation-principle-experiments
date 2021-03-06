package decaf.frontend.scope;

import decaf.frontend.symbol.LambdaSymbol;
import decaf.frontend.symbol.VarSymbol;

/**
 * Lambda scope: stores parameter variable symbols. It is owned by a lambda symbol.
 */
public class LambdaScope extends Scope {

    public LambdaScope() {
        super(Kind.LAMBDA);
    }

    public LambdaSymbol getOwner() {
        return owner;
    }

    public void setOwner(LambdaSymbol owner) {
        this.owner = owner;
    }

    @Override
    public boolean isLambdaScope() {
        return true;
    }

    /**
     * Get the local scope associated with the lambda body.
     *
     * @return local scope
     */
    public LocalScope nestedLocalScope() {
        return nested;
    }

    /**
     * Set the local scope.
     *
     * @param scope local scope
     */
    void setNested(LocalScope scope) {
        nested = scope;
    }

    private LambdaSymbol owner;

    private LocalScope nested;

    public boolean definedInLambda(String symbol) {
        if (nested.containsKey(symbol)) {
            return true;
        }
        for (var scope : nested.nested) {
            if (scope.containsKey(symbol)) {
                return true;
            }
        }
        return false;
    }
}
