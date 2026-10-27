package com.afs.guard;

import java.util.*;
import java.text.*;

/** Offline, bounded calculations shared by UI and regression tests. */
public final class ToolMath {
    private ToolMath() {}
    public static String number(double n) {
        if (!Double.isFinite(n)) throw new IllegalArgumentException("结果超出范围或运算无定义");
        if (n == 0) return "0";
        return new java.math.BigDecimal(String.format(Locale.US,"%.12g",n)).stripTrailingZeros().toString();
    }
    public static double calculate(String text, boolean degrees) {
        if(text==null || text.length()>512) throw new IllegalArgumentException("表达式最长 512 字符");
        Parser p=new Parser(text.replace('×','*').replace('÷','/').replace('−','-'),degrees);
        double n=p.sum();p.space();if(p.i!=p.s.length())throw p.error();number(n);return n;
    }
    private static final class Parser {
        final String s; final boolean deg; int i,depth;
        Parser(String s,boolean deg){this.s=s;this.deg=deg;}
        IllegalArgumentException error(){return new IllegalArgumentException("请检查表达式第 "+(i+1)+" 个字符");}
        void space(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        boolean take(char c){space();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        double sum(){double n=product();while(true){if(take('+'))n+=product();else if(take('-'))n-=product();else return n;}}
        double product(){double n=unary();while(true){if(take('*'))n*=unary();else if(take('/')){double d=unary();if(d==0)throw new IllegalArgumentException("不能除以零");n/=d;}else return n;}}
        double unary(){if(++depth>64)throw new IllegalArgumentException("表达式嵌套过深");try{if(take('+'))return unary();if(take('-'))return -unary();double n=atom();while(take('%'))n/=100; if(take('^'))n=Math.pow(n,unary());return n;}finally{depth--;}}
        double atom(){space();if(take('(')){double n=sum();if(!take(')'))throw error();return n;}
            int start=i;
            if(i<s.length()&&(Character.isLetter(s.charAt(i))||s.charAt(i)=='π')){
                while(i<s.length()&&(Character.isLetter(s.charAt(i))||s.charAt(i)=='π'))i++;
                String f=s.substring(start,i).toLowerCase(Locale.US);if(f.equals("pi")||f.equals("π"))return Math.PI;if(f.equals("e"))return Math.E;
                if(!take('('))throw error();double a=sum();if(!take(')'))throw error();double rad=deg?Math.toRadians(a):a;
                switch(f){case "sin":return Math.sin(rad);case "cos":return Math.cos(rad);case "tan":if(Math.abs(Math.cos(rad))<1e-14)throw new IllegalArgumentException("该角度的正切无定义");return Math.tan(rad);case "sqrt":return Math.sqrt(a);case "ln":return Math.log(a);case "log":return Math.log10(a);case "abs":return Math.abs(a);case "asin":return deg?Math.toDegrees(Math.asin(a)):Math.asin(a);case "acos":return deg?Math.toDegrees(Math.acos(a)):Math.acos(a);case "atan":return deg?Math.toDegrees(Math.atan(a)):Math.atan(a);default:throw new IllegalArgumentException("不支持的函数："+f);}
            }
            while(i<s.length()&&(Character.isDigit(s.charAt(i))||s.charAt(i)=='.'))i++;
            if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){i++;if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}
            if(start==i)throw error();try{return Double.parseDouble(s.substring(start,i));}catch(NumberFormatException e){throw error();}
        }
    }
    public static final String[] CATEGORIES={"长度","面积","体积","质量","时间","速度","压力","功率","能量","温度"};
    public static final String[][] UNITS={{"米 m","毫米 mm","厘米 cm","千米 km","英寸 in","英尺 ft"},{"平方米 m²","平方厘米 cm²","平方千米 km²","公顷 ha"},{"立方米 m³","升 L","毫升 mL"},{"千克 kg","克 g","吨 t","磅 lb"},{"秒 s","分钟 min","小时 h","天 d"},{"米/秒 m/s","千米/时 km/h","英里/时 mph"},{"帕 Pa","千帕 kPa","兆帕 MPa","bar","标准大气压 atm"},{"瓦 W","千瓦 kW","马力 hp"},{"焦耳 J","千焦 kJ","千瓦时 kWh","卡 cal"},{"摄氏度 °C","华氏度 °F","开尔文 K"}};
    private static final double[][] FACTORS={{1,.001,.01,1000,.0254,.3048},{1,.0001,1e6,1e4},{1,.001,.000001},{1,.001,1000,.45359237},{1,60,3600,86400},{1,1/3.6,.44704},{1,1000,1e6,1e5,101325},{1,1000,745.699871582},{1,1000,3600000,4.184}};
    public static double convert(double n,int category,int from,int to){
        if(!Double.isFinite(n)||category<0||category>=UNITS.length||from<0||to<0||from>=UNITS[category].length||to>=UNITS[category].length)throw new IllegalArgumentException("请输入有效数值和单位");
        double result;
        if(category==9){double c=from==0?n:from==1?(n-32)*5/9:n-273.15;if(c < -273.150000001)throw new IllegalArgumentException("温度不能低于绝对零度");result=to==0?c:to==1?c*9/5+32:c+273.15;}
        else result=n*FACTORS[category][from]/FACTORS[category][to];number(result);return result;
    }
    private static SimpleDateFormat formatter(){SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("UTC"));return f;}
    private static Date date(String s){if(s==null||!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new IllegalArgumentException("日期格式为 YYYY-MM-DD");try{return formatter().parse(s);}catch(ParseException e){throw new IllegalArgumentException("日期不存在");}}
    public static long days(String a,String b){return (date(b).getTime()-date(a).getTime())/86400000L;}
    public static String addDays(String a,int days){if(Math.abs((long)days)>365000)throw new IllegalArgumentException("加减天数最多 365000 天");Calendar c=new GregorianCalendar(TimeZone.getTimeZone("UTC"));c.setTime(date(a));c.add(Calendar.DAY_OF_MONTH,days);if(c.get(Calendar.ERA)!=GregorianCalendar.AD||c.get(Calendar.YEAR)>9999)throw new IllegalArgumentException("日期超出范围");return formatter().format(c.getTime());}
    public static int[] pages(String s,int count){
        if(count<1||count>500)throw new IllegalArgumentException("最多处理 500 页");
        if(s==null||s.trim().isEmpty()){int[] all=new int[count];for(int i=0;i<count;i++)all[i]=i;return all;}
        if(s.length()>2000)throw new IllegalArgumentException("页码范围过长");LinkedHashSet<Integer> result=new LinkedHashSet<>();
        try{for(String part:s.split(",",-1)){String[] ends=part.trim().split("-",-1);if(ends.length>2)throw new NumberFormatException();int a=Integer.parseInt(ends[0].trim()),b=ends.length==1?a:Integer.parseInt(ends[1].trim());if(a<1||b<a||b>count)throw new NumberFormatException();for(int i=a;i<=b;i++)result.add(i-1);}}
        catch(NumberFormatException e){throw new IllegalArgumentException("页码请输入 1,3-5，且不超过文档页数");}
        int[] out=new int[result.size()];int i=0;for(int p:result)out[i++]=p;return out;
    }
}
