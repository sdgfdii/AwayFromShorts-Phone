package com.afs.guard;

import java.util.ArrayList;
import java.util.List;

/** Small offline Markdown tables. Row zero is the header; there is no separate table store. */
public final class MemoTable {
    private MemoTable() { }
    public static final int MAX_ROWS=20,MAX_COLUMNS=10,MAX_CELL=1000,MAX_TEXT=20000;
    public static final class Model {
        public final List<List<String>> cells=new ArrayList<>();
    }
    public static Model create(int rows,int columns){
        dimensions(rows,columns);Model model=new Model();
        for(int r=0;r<rows;r++){List<String> row=new ArrayList<>();for(int c=0;c<columns;c++)row.add("");model.cells.add(row);}
        return model;
    }
    private static void dimensions(int rows,int columns){
        if(rows<1||rows>MAX_ROWS||columns<1||columns>MAX_COLUMNS)throw new IllegalArgumentException(T.s("表格须为 1 至 20 行、1 至 10 列（含表头）"));
    }
    private static String cell(String value){
        if(value==null)throw new IllegalArgumentException(T.s("单元格不能为空值"));
        if(value.length()>MAX_CELL)throw new IllegalArgumentException(T.s("单元格最多 1000 字"));
        for(int i=0;i<value.length();i++){
            char ch=value.charAt(i);
            if(ch=='|'||Character.isISOControl(ch)||ch=='\u2028'||ch=='\u2029')throw new IllegalArgumentException(T.s("单元格不能包含换行、竖线或控制字符"));
        }
        return value.trim();
    }
    private static Model copy(Model model){
        if(model==null||model.cells.isEmpty()||model.cells.get(0)==null)throw new IllegalArgumentException(T.s("表格数据无效"));
        int columns=model.cells.get(0).size();dimensions(model.cells.size(),columns);Model result=new Model();
        for(List<String> row:model.cells){
            if(row==null||row.size()!=columns)throw new IllegalArgumentException(T.s("表格每行列数必须一致"));
            List<String> next=new ArrayList<>();for(String value:row)next.add(cell(value));result.cells.add(next);
        }return result;
    }
    private static void index(int index,int size,boolean insert){
        if(index<0||index>(insert?size:size-1))throw new IllegalArgumentException(T.s("表格位置无效"));
    }
    /** Reject unsupported content before changing any existing cell or table structure. */
    private static void commit(Model target,Model candidate){
        encode(candidate);target.cells.clear();target.cells.addAll(candidate.cells);
    }
    public static void setCell(Model model,int row,int column,String value){
        Model candidate=copy(model);index(row,candidate.cells.size(),false);index(column,candidate.cells.get(0).size(),false);
        candidate.cells.get(row).set(column,cell(value));commit(model,candidate);
    }
    public static void addRow(Model model){addRow(model,model==null?0:model.cells.size());}
    public static void addRow(Model model,int at){
        Model candidate=copy(model);index(at,candidate.cells.size(),true);dimensions(candidate.cells.size()+1,candidate.cells.get(0).size());
        List<String> row=new ArrayList<>();for(int c=0;c<candidate.cells.get(0).size();c++)row.add("");candidate.cells.add(at,row);commit(model,candidate);
    }
    public static void addColumn(Model model){addColumn(model,model==null||model.cells.isEmpty()?0:model.cells.get(0).size());}
    public static void addColumn(Model model,int at){
        Model candidate=copy(model);index(at,candidate.cells.get(0).size(),true);dimensions(candidate.cells.size(),candidate.cells.get(0).size()+1);
        for(List<String> row:candidate.cells)row.add(at,"");commit(model,candidate);
    }
    public static void removeRow(Model model,int at){
        Model candidate=copy(model);index(at,candidate.cells.size(),false);dimensions(candidate.cells.size()-1,candidate.cells.get(0).size());
        candidate.cells.remove(at);commit(model,candidate);
    }
    public static void removeColumn(Model model,int at){
        Model candidate=copy(model);index(at,candidate.cells.get(0).size(),false);dimensions(candidate.cells.size(),candidate.cells.get(0).size()-1);
        for(List<String> row:candidate.cells)row.remove(at);commit(model,candidate);
    }
    public static String encode(Model model){
        Model normalized=copy(model);StringBuilder out=new StringBuilder();
        for(int r=0;r<normalized.cells.size();r++){
            if(r>0)out.append('\n');appendRow(out,normalized.cells.get(r));
            if(r==0){out.append('\n');List<String> separator=new ArrayList<>();for(int c=0;c<normalized.cells.get(0).size();c++)separator.add("---");appendRow(out,separator);}
            if(out.length()>MAX_TEXT)throw new IllegalArgumentException(T.s("表格超过正文 2 万字上限"));
        }return out.toString();
    }
    private static void appendRow(StringBuilder out,List<String> row){out.append('|');for(String value:row)out.append(' ').append(value).append(" |");}
    private static List<String> parseRow(String line){
        String clean=line.trim();if(!clean.contains("|")||clean.contains("\\|"))throw new IllegalArgumentException(T.s("未找到可编辑表格，或包含不支持的竖线转义"));
        String[] parts=clean.split("\\|",-1);int from=clean.startsWith("|")?1:0,to=parts.length-(clean.endsWith("|")?1:0);
        List<String> row=new ArrayList<>();for(int i=from;i<to;i++)row.add(cell(parts[i]));return row;
    }
    /** The input must contain exactly one table, with optional surrounding blank lines. */
    public static Model parse(String text){
        if(text==null||text.length()>MAX_TEXT)throw new IllegalArgumentException(T.s("表格文本无效或超过 2 万字"));
        String normalized=text.replace("\r\n","\n");String[] lines=normalized.split("\n",-1);int first=0,last=lines.length;
        while(first<last&&lines[first].trim().isEmpty())first++;while(last>first&&lines[last-1].trim().isEmpty())last--;
        if(last-first<2)throw new IllegalArgumentException(T.s("未找到 Markdown 表格（需要表头与分隔行）"));
        List<String> header=parseRow(lines[first]),separator=parseRow(lines[first+1]);
        if(header.size()!=separator.size())throw new IllegalArgumentException(T.s("表格分隔行列数不一致"));
        for(String value:separator)if(!value.matches(":?-{3,}:?"))throw new IllegalArgumentException(T.s("表格分隔行须使用 ---"));
        dimensions(last-first-1,header.size());Model model=new Model();model.cells.add(header);
        for(int r=first+2;r<last;r++){
            List<String> row=parseRow(lines[r]);if(row.size()!=header.size())throw new IllegalArgumentException(T.s("表格每行列数必须一致"));model.cells.add(row);
        }
        // Covers a separator longer than the canonical output, cell restrictions, and all dimensions.
        encode(model);return model;
    }
}
