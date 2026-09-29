package app.pfandcounter;
import java.lang.reflect.*;
import java.util.*;
/** Walks every path of DepositGuide.ROOT on the laptop, naming labels via R$string. */
public class GuideTest {
    static Map<Integer,String> names = new HashMap<>();
    public static void main(String[] a) throws Exception {
        for (Field f : Class.forName("app.pfandcounter.R$string").getFields()) names.put(f.getInt(null), f.getName());
        Field root = DepositGuide.class.getDeclaredField("ROOT"); root.setAccessible(true);
        List<String> out = new ArrayList<>();
        walk(root.get(null), "", out, 0);
        for (String s : out) System.out.println(s);
        System.out.println("Pfade: " + out.size());
    }
    static void walk(Object step, String prefix, List<String> out, int depth) throws Exception {
        if (depth > 5) throw new IllegalStateException("zu tief: " + prefix);
        Class<?> c = step.getClass();
        Field of = c.getDeclaredField("options"); of.setAccessible(true);
        Object[] opts = (Object[]) of.get(step);
        if (opts == null) {
            Field cf = c.getDeclaredField("cents"); cf.setAccessible(true);
            Field rf = c.getDeclaredField("reason"); rf.setAccessible(true);
            String why = names.get(rf.getInt(step));
            if (why == null) throw new IllegalStateException("Ergebnis ohne Begründung: " + prefix);
            out.add(prefix + "  =  " + cf.getInt(step) + " ct   (" + why + ")");
            return;
        }
        if (opts.length == 0) throw new IllegalStateException("Frage ohne Antworten: " + prefix);
        for (Object o : opts) {
            Field lf = o.getClass().getDeclaredField("label"); lf.setAccessible(true);
            Field nf = o.getClass().getDeclaredField("next"); nf.setAccessible(true);
            walk(nf.get(o), prefix + " > " + names.get(lf.getInt(o)), out, depth + 1);
        }
    }
}
