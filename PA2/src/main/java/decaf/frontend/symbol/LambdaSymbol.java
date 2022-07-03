package decaf.frontend.symbol;

import decaf.frontend.scope.ClassScope;
import decaf.frontend.scope.LambdaScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.FunType;

import java.util.ArrayList;

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

    public ArrayList<Tree.Return> returnStmts = new ArrayList<>();

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


}
