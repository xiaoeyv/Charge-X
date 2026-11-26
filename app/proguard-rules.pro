# 自定义 View 由 XML 反射实例化，类名与三种构造器都必须保留
-keep public class com.xiaoeyv.chargex.*View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepattributes *Annotation*
-dontwarn android.**
