package plc.project.analyzer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Environment {

    public static final Map<String, Type> TYPES = Stream.of(
        Type.ANY,
        Type.NIL,
        Type.DYNAMIC,

        Type.BOOLEAN,
        Type.INTEGER,
        Type.DECIMAL,
        Type.CHARACTER,
        Type.STRING,

        Type.EQUATABLE,
        Type.COMPARABLE,
        Type.ITERABLE
    ).collect(Collectors.toMap(Type.Primitive::name, t -> t));

    public static Scope scope() {
        var scope = new Scope(null);
        TYPES.forEach(scope::define);
        //Helper variables for testing non-literal types;
        scope.define("any", Type.ANY);
        scope.define("dynamic", Type.DYNAMIC);
        scope.define("equatable", Type.EQUATABLE);
        scope.define("comparable", Type.COMPARABLE);
        scope.define("iterable", Type.ITERABLE);
        scope.define("log", new Type.Function(List.of(Type.ANY), Type.DYNAMIC)); //note Dynamic!
        scope.define("debug", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("print", new Type.Function(List.of(Type.ANY), Type.NIL));
        scope.define("range", new Type.Function(List.of(Type.INTEGER, Type.INTEGER), Type.ITERABLE));
        scope.define("variable", Type.STRING);
        scope.define("function", new Type.Function(List.of(), Type.NIL));
        scope.define("functionAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        scope.define("functionString", new Type.Function(List.of(Type.STRING), Type.STRING));
        var prototype = new Type.ObjectType(Optional.of("Prototype"), new Scope(null));
        prototype.scope().define("inherited_property", Type.STRING);
        prototype.scope().define("inherited_method", new Type.Function(List.of(), Type.NIL));
        var object = new Type.ObjectType(Optional.of("Object"), new Scope(null));
        scope.define("object", object);
        object.scope().define("prototype", prototype);
        object.scope().define("property", Type.STRING);
        object.scope().define("method", new Type.Function(List.of(), Type.NIL));
        object.scope().define("methodAny", new Type.Function(List.of(Type.ANY), Type.ANY));
        object.scope().define("methodString", new Type.Function(List.of(Type.STRING), Type.STRING));
        return scope;
    }

    public static boolean isSubtypeOf(Type subtype, Type supertype) {
        if (subtype == supertype || subtype.equals(supertype)) {
            return true;
        }

        if (supertype.equals(Type.ANY) || supertype.equals(Type.DYNAMIC)) {
            return true;
        }

        if (subtype.equals(Type.DYNAMIC)) {
            return true;
        }

        if (subtype.equals(Type.NIL)) {
            // Nil is a subtype of Equatable
            if (supertype.equals(Type.EQUATABLE)) {
                return true;
            }
            return false;
        }

        // Get type names for comparison
        if (subtype instanceof Type.Primitive subPrim && supertype instanceof Type.Primitive superPrim) {
            String subName = subPrim.name();
            String superName = superPrim.name();

            if (superName.equals("Equatable")) {
                return subName.equals("String") || subName.equals("Integer") ||
                       subName.equals("Decimal") || subName.equals("Character") ||
                       subName.equals("Boolean") || subName.equals("Comparable") ||
                       subName.equals("Iterable") || subName.equals("Nil");
            }

            if (superName.equals("Comparable")) {
                return subName.equals("String") || subName.equals("Integer") ||
                       subName.equals("Decimal") || subName.equals("Character");
            }

            if (superName.equals("Iterable")) {
                return subName.equals("Iterable");
            }
        }

        // Check for prototypal inheritance
        if (subtype instanceof Type.ObjectType subObj && supertype instanceof Type.ObjectType superObj) {
            // Check if subtype has a prototype chain leading to supertype
            return isPrototypeOf(subObj, superObj);
        }

        // Dynamic prototype case
        if (subtype instanceof Type.ObjectType subObj && supertype instanceof Type.ObjectType) {
            var prototypeOpt = subObj.scope().resolve("prototype", false);
            if (prototypeOpt.isPresent() && prototypeOpt.get().equals(Type.DYNAMIC)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPrototypeOf(Type.ObjectType subtype, Type.ObjectType supertype) {
        var prototypeOpt = subtype.scope().resolve("prototype", false);
        if (prototypeOpt.isEmpty()) {
            return false;
        }

        var prototypeType = prototypeOpt.get();
        if (prototypeType instanceof Type.ObjectType prototypeObj) {
            if (prototypeObj.equals(supertype)) {
                return true;
            }
            // Recursively check the prototype chain
            return isPrototypeOf(prototypeObj, supertype);
        } else if (prototypeType.equals(Type.DYNAMIC)) {
            // Dynamic prototype is considered a supertype of empty object
            return supertype.scope().collect(false).isEmpty();
        }

        return false;
    }

}