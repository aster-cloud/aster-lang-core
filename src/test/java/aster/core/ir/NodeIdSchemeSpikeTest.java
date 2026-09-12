package aster.core.ir;
import aster.core.canonicalizer.Canonicalizer;
import aster.core.ir.CoreModel;
import aster.core.lowering.CoreLowering;
import aster.core.parser.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.antlr.v4.runtime.*;
import org.junit.jupiter.api.Test;
import java.security.MessageDigest;
import java.util.*;

/**
 * Spike：ADR 0037 步骤 4「Stable IR Node IDs」的方案选型实测。
 *
 * <h2>要回答的问题</h2>
 *
 * ADR 0037 §7 待拍板第 2 条把它列为「尚未展开设计的真实权衡」：
 * <pre>
 *   基于结构 hash（改一处则 ID 变，不利于 change impact）
 *   还是基于路径/序号（对重排不稳定）？
 * </pre>
 *
 * <h2>本测试的作用</h2>
 *
 * 它<b>不是</b>回归守卫，而是一份<b>可复跑的证据</b>：把三种候选方案放在同一组
 * 编辑下量化「节点身份存活率」，让选型基于数据而非直觉。任何人改了 ID 方案，
 * 重跑本测试即可看到新方案在六种编辑下的表现。
 *
 * <p>★因此它只打印矩阵、只断言「测量装置本身有效」（每个编辑确实改动了源码），
 * 不断言某个存活率数字——那会把某一次的实测值当成契约锁死。
 */
public class NodeIdSchemeSpikeTest {

  static JsonNode ir(String src) throws Exception {
    String canon = new Canonicalizer().canonicalize(src);
    var lexer = new AsterCustomLexer(CharStreams.fromString(canon));
    lexer.removeErrorListeners();
    var parser = new AsterParser(new CommonTokenStream(lexer));
    parser.removeErrorListeners();
    CoreModel.Module m = new CoreLowering().lowerModule(new AstBuilder().visitModule(parser.module()));
    return new ObjectMapper().valueToTree(m);
  }

  static String sha(String s) throws Exception {
    var md = MessageDigest.getInstance("SHA-256");
    StringBuilder sb=new StringBuilder();
    for(byte b: md.digest(s.getBytes("UTF-8"))) sb.append(String.format("%02x",b));
    return sb.substring(0,8);
  }

  /** 方案 A：结构 hash —— 节点子树内容的 hash（剥掉 origin）。 */
  static void structIds(JsonNode n, String path, Map<String,String> out) throws Exception {
    if (n.isObject()) {
      ObjectNode c = ((ObjectNode) n).deepCopy(); c.remove("origin");
      // 子节点的 origin 也要剥掉，否则「结构 hash」其实混了位置
      stripOrigin(c);
      if (n.has("kind")) out.put(path, sha(c.toString()));
      var it=n.fields();
      while(it.hasNext()){ var e=it.next(); if(!e.getKey().equals("origin")) structIds(e.getValue(), path+"."+e.getKey(), out); }
    } else if (n.isArray()) {
      for(int i=0;i<n.size();i++) structIds(n.get(i), path+"["+i+"]", out);
    }
  }
  static void stripOrigin(JsonNode n){
    if(n.isObject()){ ((ObjectNode)n).remove("origin"); n.forEach(NodeIdSchemeSpikeTest::stripOrigin); }
    else if(n.isArray()) n.forEach(NodeIdSchemeSpikeTest::stripOrigin);
  }

  /** 方案 B：结构路径 —— 从根到节点的 JSON 路径（不含数组下标之外的位置信息）。 */
  static void pathIds(JsonNode n, String path, Map<String,String> out) {
    if (n.isObject()) {
      if (n.has("kind")) out.put(path, path);
      var it=n.fields();
      while(it.hasNext()){ var e=it.next(); if(!e.getKey().equals("origin")) pathIds(e.getValue(), path+"."+e.getKey(), out); }
    } else if (n.isArray()) {
      for(int i=0;i<n.size();i++) pathIds(n.get(i), path+"["+i+"]", out);
    }
  }


  /** 方案 C（混合）：命名作用域路径 —— 路径段优先用**声明名**，无名时才退回下标。 */
  static void namedPathIds(JsonNode n, String path, Map<String,String> out) {
    if (n.isObject()) {
      if (n.has("kind")) out.put(path, path);
      var it = n.fields();
      while (it.hasNext()) {
        var e = it.next();
        if (e.getKey().equals("origin")) continue;
        JsonNode v = e.getValue();
        if (v.isArray()) {
          for (int i = 0; i < v.size(); i++) {
            JsonNode el = v.get(i);
            // ★关键：数组元素若有 name，用 name 作路径段；否则退回下标
            String seg = (el.isObject() && el.hasNonNull("name"))
                ? "{" + el.get("name").asText() + "}" : "[" + i + "]";
            namedPathIds(el, path + "." + e.getKey() + seg, out);
          }
        } else {
          namedPathIds(v, path + "." + e.getKey(), out);
        }
      }
    }
  }

  @Test void compare() throws Exception {
    String v1 = """
      Module demo.approve.

      Rule approve given amount, produce:
        If amount greater than 10000:
          Return "REFER".
        Otherwise:
          Return "APPROVE".
      """;
    // 真实编辑 1：改阈值（change-impact 的典型场景）
    String v2 = v1.replace("10000", "20000");
    // 真实编辑 2：在**前面**插入一条无关规则（重排场景）
    String v3 = v1.replace("Rule approve",
        "Rule noop, produce:\n  Return \"X\".\n\nRule approve");

    // ★对抗性编辑：专挑方案 C 可能垮掉的场景
    String v4 = v1.replace("Rule approve given amount", "Rule assess given amount");   // 重命名规则
    String v5 = v1.replace("Return \"REFER\".", "Return \"REFER2\".");                  // 改分支内字面量
    // 文本块已去公共缩进，Otherwise 实际缩进为 2 空格
    String v6 = v1.replace("  Otherwise:\n    Return \"APPROVE\".\n", "");   // 删掉 else 分支
    String v7 = v1.replace("  If amount greater than 10000:",
        "  If amount greater than 5000:\n    Return \"LOW\".\n  If amount greater than 10000:"); // 中间插分支

    // ★自检：若某个 replace 没匹配上，测的就是「未编辑」，结果 16/16 是假的
    for (String[] chk : new String[][]{{"v2",v2},{"v3",v3},{"v4",v4},{"v5",v5},{"v6",v6},{"v7",v7}}) {
      // ★这条断言守的是**测量装置**，不是被测方案：replace 若没匹配上，
      //   该行测的就是「未编辑」，会得到一个假的 16/16。实测中 v6 正是这样
      //   翻车过一次（文本块已去公共缩进，实际缩进与写死的不符）。
      org.junit.jupiter.api.Assertions.assertNotEquals(v1, chk[1],
          chk[0] + " 与基线相同——该编辑的 replace 未匹配，它那一行的存活率是假数据。");
    }

    for (String[] pair : new String[][]{
        {"改阈值 10000→20000", v2}, {"前面插入一条无关规则", v3},
        {"★重命名规则 approve→assess", v4}, {"★改分支内字面量", v5},
        {"★删掉 else 分支", v6}, {"★中间插入一个新分支", v7}}) {
      Map<String,String> a1=new TreeMap<>(), a2=new TreeMap<>(), b1=new TreeMap<>(), b2=new TreeMap<>(),
                         c1=new TreeMap<>(), c2=new TreeMap<>();
      structIds(ir(v1), "$", a1); structIds(ir(pair[1]), "$", a2);
      pathIds(ir(v1), "$", b1);   pathIds(ir(pair[1]), "$", b2);
      namedPathIds(ir(v1), "$", c1); namedPathIds(ir(pair[1]), "$", c2);
      System.out.println("\n=== 编辑：" + pair[0] + " ===");
      System.out.println("  基线节点数 " + a1.size() + " → 新版 " + a2.size());
      System.out.printf("  方案A 结构hash：存活 %d / %d%n", countSame(a1,a2), a1.size());
      System.out.printf("  方案B 结构路径：存活 %d / %d%n", countSame(b1,b2), b1.size());
      System.out.printf("  方案C 命名作用域路径：存活 %d / %d%n", countSame(c1,c2), c1.size());
    }
  }
  static int countSame(Map<String,String> x, Map<String,String> y){
    Set<String> vx=new HashSet<>(x.values()); int n=0;
    for(String v: y.values()) if(vx.contains(v)) n++;
    return n;
  }
}
