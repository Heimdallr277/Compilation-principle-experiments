package decaf.frontend.symbol;

import decaf.frontend.scope.ClassScope;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.type.FunType;

import java.util.TreeMap;

/**
 * Method symbol, representing a method definition.
 */
public final class LambdaSymbol extends Symbol {

    public FunType type;

    /**
     * Associated lambda scope of the method parameters.
     */
    public final LambdaScope scope;

    public final ClassSymbol owner;

    public TreeMap<VarSymbol, Integer> capturedVars = new TreeMap<>();

    public boolean captureThis = false;

    private int capturedVarsSum = 0;

    public LambdaSymbol(String name, FunType type, LambdaScope scope, Pos pos, ClassSymbol owner) {
        super(name, type, pos);
        this.type = type;
        this.scope = scope;
        this.owner = owner;
        scope.setOwner(this);
    }

    @Override
    public ClassScope domain() {
        return (ClassScope) definedIn;
    }

    @Override
    public boolean isLambdaSymbol() {
        return true;
    }

    @Override
    protected String str() {
        return String.format("function %s : %s", name, type);
    }

    public void capture(VarSymbol symbol) {
        if (!capturedVars.containsKey(symbol)) {
            capturedVars.put(symbol, capturedVarsSum);
            capturedVarsSum++;
        }
    }

    public int getCapturedVarsSum() {
        return captureThis ? capturedVars.size() + 1 : capturedVars.size();
    }

}
