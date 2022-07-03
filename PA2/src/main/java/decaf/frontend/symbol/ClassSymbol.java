package decaf.frontend.symbol;

import decaf.frontend.scope.ClassScope;
import decaf.frontend.scope.GlobalScope;
import decaf.frontend.tree.Pos;
import decaf.frontend.tree.Tree;
import decaf.frontend.type.ClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Class symbol, representing a class definition.
 */
public final class ClassSymbol extends Symbol {

    public final Optional<ClassSymbol> parentSymbol;

    public final ClassType type;

    public final Tree.Modifiers modifiers;

    public ArrayList<String> absMethodsToOverride;

    /**
     * Associated class scope of this class.
     */
    public final ClassScope scope;

    public ClassSymbol(String name, ClassType type, ClassScope scope, Pos pos, Tree.Modifiers modifiers) {
        super(name, type, pos);
        this.parentSymbol = Optional.empty();
        this.scope = scope;
        this.type = type;
        this.modifiers = modifiers;
        this.absMethodsToOverride = new ArrayList<>();
        scope.setOwner(this);
    }

    public ClassSymbol(String name, ClassSymbol parentSymbol, ClassType type, ClassScope scope, Pos pos, Tree.Modifiers modifiers) {
        super(name, type, pos);
        this.parentSymbol = Optional.of(parentSymbol);
        this.scope = scope;
        this.type = type;
        this.modifiers = modifiers;
        this.absMethodsToOverride = parentSymbol.absMethodsToOverride;
        scope.setOwner(this);
    }

    @Override
    public GlobalScope domain() {
        return (GlobalScope) definedIn;
    }

    @Override
    public boolean isClassSymbol() {
        return true;
    }

    /**
     * Set as main class, by {@link decaf.frontend.typecheck.Namer}.
     */
    public void setMainClass() {
        main = true;
    }

    /**
     * Is it a main function?
     *
     * @return true/false
     */
    public boolean isMainClass() {
        return main;
    }

    @Override
    protected String str() {
        var modStr = modifiers.toString();
        if (!modStr.isEmpty()) modStr += " ";
        return modStr + "class " + name + parentSymbol.map(classSymbol -> " : " + classSymbol.name).orElse("");
    }

    public boolean isAbstract() { return modifiers.isAbstract(); }

    private boolean main;
}
