package io.mirage.harddecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 类结构（静态提取，Gson 可直接序列化）。
 * 类名采用 JVM 内部形式（斜杠分隔）。
 */
public class ClassStructure {
    public String name;
    public String superClass;
    public List<String> interfaces = new ArrayList<>();
    public List<String> access = new ArrayList<>();
    public List<String> annotations = new ArrayList<>();
    public List<MethodInfo> methods = new ArrayList<>();
    public List<FieldInfo> fields = new ArrayList<>();

    public ClassStructure() {}

    public ClassStructure(String name, String superClass) {
        this.name = name;
        this.superClass = superClass;
    }
}
