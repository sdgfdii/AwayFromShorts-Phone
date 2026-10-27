package com.afs.guard;
import java.util.Arrays;
public final class ToolMathTest {
    static int checks;
    static void near(double got,double expected){checks++;if(Math.abs(got-expected)>1e-8*Math.max(1,Math.abs(expected)))throw new AssertionError(got+" != "+expected);}
    static void yes(boolean ok){checks++;if(!ok)throw new AssertionError();}
    static void rejects(Runnable task){checks++;try{task.run();}catch(IllegalArgumentException e){return;}throw new AssertionError("Expected validation failure");}
    public static void main(String[]args){
        near(ToolMath.calculate("2+3*4",true),14);near(ToolMath.calculate("(2+3)*4",true),20);
        near(ToolMath.calculate("-2^2",true),-4);near(ToolMath.calculate("2^3^2",true),512);near(ToolMath.calculate("2^-2",true),.25);
        near(ToolMath.calculate("sin(30)+cos(60)",true),1);near(ToolMath.calculate("sin(pi/2)",false),1);
        near(ToolMath.calculate("sqrt(9)+ln(e)+log(100)",true),6);near(ToolMath.calculate("200*10%",true),20);near(ToolMath.calculate("1e-3+2E2",true),200.001);
        rejects(()->ToolMath.calculate("1/0",true));rejects(()->ToolMath.calculate("sqrt(-1)",true));rejects(()->ToolMath.calculate("tan(90)",true));rejects(()->ToolMath.calculate("2foo",true));rejects(()->ToolMath.calculate("1..2",true));rejects(()->ToolMath.calculate("(2+3",true));rejects(()->ToolMath.calculate("",true));rejects(()->ToolMath.calculate("9^9999",true));
        near(ToolMath.convert(1,0,4,2),2.54);near(ToolMath.convert(36,5,1,0),10);near(ToolMath.convert(1,6,4,0),101325);near(ToolMath.convert(1,8,2,0),3600000);
        near(ToolMath.convert(32,9,1,0),0);near(ToolMath.convert(273.15,9,2,0),0);near(ToolMath.convert(100,9,0,1),212);rejects(()->ToolMath.convert(-1,9,2,0));rejects(()->ToolMath.convert(Double.NaN,0,0,1));
        for(int c=0;c<ToolMath.CATEGORIES.length;c++)for(int u=0;u<ToolMath.UNITS[c].length;u++)near(ToolMath.convert(ToolMath.convert(300,c,0,u),c,u,0),300);
        yes(ToolMath.days("2024-02-28","2024-03-01")==2);yes(ToolMath.days("2025-03-01","2025-02-28")==-1);yes(ToolMath.days("2026-10-26","2026-10-26")==0);yes(ToolMath.addDays("2024-02-28",1).equals("2024-02-29"));yes(ToolMath.addDays("2026-01-01",-1).equals("2025-12-31"));rejects(()->ToolMath.days("2025-02-29","2025-03-01"));rejects(()->ToolMath.days("2026-1-01","2026-02-01"));
        yes(Arrays.equals(ToolMath.pages("3,1-2,2",3),new int[]{2,0,1}));yes(ToolMath.pages("",3).length==3);rejects(()->ToolMath.pages("0",3));rejects(()->ToolMath.pages("3-2",3));rejects(()->ToolMath.pages("4",3));rejects(()->ToolMath.pages("1,",3));rejects(()->ToolMath.pages("",501));
        System.out.println("ToolMath: "+checks+" checks passed");
    }
}
