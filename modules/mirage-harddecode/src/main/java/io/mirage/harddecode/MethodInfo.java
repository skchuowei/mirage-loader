package io.mirage.harddecode;

import java.util.ArrayList;
import java.util.List;

/**
 * 方法结构（静态提取，Gson 可直接序列化）。
 */
public class MethodInfo {
    public String name;
    public String desc;
    public List<String> access = new ArrayList<>();
    public List<String> annotations = new ArrayList<>();

    public MethodInfo() {}

    public MethodInfo(String name, String desc, List<String> access, List<String> annotations) {
        this.name = name;
        this.desc = desc;
        this.access = access;
        this.annotations = annotations;
    }
}
