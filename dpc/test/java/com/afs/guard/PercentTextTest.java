package com.afs.guard;

/** 打卡页完成度：一位小数，向下不丢精度，边界全部按 0.0 兜底。 */
public final class PercentTextTest {
    private static int checks;
    private static void eq(String expected,String actual,String label){checks++;if(!expected.equals(actual))throw new AssertionError(label+": "+actual+" != "+expected);}
    public static void main(String[] args){
        eq("33.3",PercentText.of(1,3),"one third");
        eq("66.7",PercentText.of(2,3),"two thirds");
        eq("50.0",PercentText.of(1,2),"half keeps one decimal");
        eq("100.0",PercentText.of(4,4),"complete");
        eq("0.0",PercentText.of(0,4),"nothing done");
        eq("0.0",PercentText.of(3,0),"zero target");
        eq("0.0",PercentText.of(3,-2),"negative target");
        eq("0.0",PercentText.of(-1,4),"negative progress");
        eq("0.0",PercentText.of(Double.NaN,4),"nan progress");
        eq("12.5",PercentText.of(1,8),"small step shows decimals");
        eq("133.3",PercentText.of(4,3),"over target keeps real value");
        eq("33.3%",PercentText.percent(1,3),"percent suffix");
        eq("0.0%",PercentText.percent(0,0),"percent suffix zero");
        System.out.println("Percent text: "+checks+" checks passed");
    }
}
