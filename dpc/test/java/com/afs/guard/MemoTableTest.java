package com.afs.guard;

public final class MemoTableTest {
    private static int checks;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    private static void eq(String expected,String actual,String label){check(expected.equals(actual),label+" expected="+expected+" actual="+actual);}
    private static void rejects(Runnable action,String label){boolean failed=false;try{action.run();}catch(IllegalArgumentException e){failed=true;}check(failed,label);}
    private static String repeated(char value,int size){StringBuilder s=new StringBuilder();for(int i=0;i<size;i++)s.append(value);return s.toString();}
    public static void main(String[] args){
        MemoTable.Model model=MemoTable.create(2,2);
        eq("|  |  |\n| --- | --- |\n|  |  |",MemoTable.encode(model),"stable empty table");
        MemoTable.setCell(model,0,0," 名称 ");MemoTable.setCell(model,0,1,"数量");MemoTable.setCell(model,1,0,"资料");MemoTable.setCell(model,1,1,"3");
        String original="| 名称 | 数量 |\n| --- | --- |\n| 资料 | 3 |";
        eq(original,MemoTable.encode(model),"cell updates and surrounding padding normalization");
        eq(original,MemoTable.encode(MemoTable.parse(original)),"encode parse roundtrip");
        eq(original,MemoTable.encode(MemoTable.parse("\n"+original.replace("\n","\r\n")+"\r\n\n")),"CRLF and surrounding blank lines");
        eq(original,MemoTable.encode(MemoTable.parse("名称 | 数量\n:--- | ---:\n资料 | 3")),"optional border pipes and alignment separator canonicalization");
        check(MemoTable.parse("| a | b |\n| --- | :---: |").cells.size()==1,"header-only table supported");
        MemoTable.Model single=MemoTable.create(1,1);eq("|  |\n| --- |",MemoTable.encode(single),"one-cell table");
        check(MemoTable.parse(MemoTable.encode(single)).cells.get(0).size()==1,"single cell roundtrip");
        MemoTable.addRow(model,1);check(model.cells.size()==3&&model.cells.get(1).get(0).isEmpty()&&model.cells.get(2).get(0).equals("资料"),"insert row at selection");
        MemoTable.removeRow(model,1);eq(original,MemoTable.encode(model),"row insertion removal preserves existing cells");
        MemoTable.addColumn(model,1);check(model.cells.get(0).size()==3&&model.cells.get(1).get(2).equals("3"),"insert column for every row");
        MemoTable.removeColumn(model,1);eq(original,MemoTable.encode(model),"column insertion removal preserves data");
        MemoTable.addRow(model);MemoTable.addColumn(model);check(model.cells.size()==3&&model.cells.get(0).size()==3,"append overloads");
        MemoTable.removeRow(model,2);MemoTable.removeColumn(model,2);eq(original,MemoTable.encode(model),"append removal roundtrip");
        MemoTable.Model promotion=MemoTable.parse(original);MemoTable.removeRow(promotion,0);eq("| 资料 | 3 |\n| --- | --- |",MemoTable.encode(promotion),"removing header promotes next row");
        rejects(()->MemoTable.removeRow(single,0),"cannot remove last row");
        rejects(()->MemoTable.removeColumn(single,0),"cannot remove last column");
        rejects(()->MemoTable.create(0,1),"zero rows rejected");rejects(()->MemoTable.create(1,0),"zero columns rejected");
        rejects(()->MemoTable.create(21,1),"row cap enforced");rejects(()->MemoTable.create(1,11),"column cap enforced");
        MemoTable.Model maximum=MemoTable.create(20,10);check(maximum.cells.size()==20&&maximum.cells.get(0).size()==10,"maximum table allowed");
        String maxBefore=MemoTable.encode(maximum);rejects(()->MemoTable.addRow(maximum),"add beyond row cap");rejects(()->MemoTable.addColumn(maximum),"add beyond column cap");eq(maxBefore,MemoTable.encode(maximum),"failed resize retains all data");
        rejects(()->MemoTable.setCell(model,-1,0,"x"),"negative row rejected");rejects(()->MemoTable.setCell(model,0,2,"x"),"out of range column rejected");
        rejects(()->MemoTable.addRow(model,3),"invalid insertion index rejected");
        rejects(()->MemoTable.setCell(model,1,0,"x|y"),"literal pipe rejected");
        rejects(()->MemoTable.setCell(model,1,0,"x\ny"),"LF cell rejected");rejects(()->MemoTable.setCell(model,1,0,"x\ry"),"CR cell rejected");
        rejects(()->MemoTable.setCell(model,1,0,"x\ty"),"control characters rejected");rejects(()->MemoTable.setCell(model,1,0,"x\u2028y"),"unicode line separator rejected");
        rejects(()->MemoTable.setCell(model,1,0,null),"null cell rejected");rejects(()->MemoTable.setCell(model,1,0,repeated('a',1001)),"cell length cap");
        eq(original,MemoTable.encode(model),"all invalid edits preserve original table");
        rejects(()->MemoTable.parse("ordinary paragraph"),"no table produces explicit error");
        rejects(()->MemoTable.parse("a | b\n-- | ---"),"invalid separator rejected");
        rejects(()->MemoTable.parse("a | b\n--- | ---\nx"),"non-table row rejected");
        rejects(()->MemoTable.parse("a | b\n--- | ---\nx | y | z"),"ragged rows rejected");
        rejects(()->MemoTable.parse("a | b\n--- | ---\nx\\|y | z"),"unsupported escaped pipe not silently split");
        rejects(()->MemoTable.parse("prefix\n"+original),"surrounding nonblank paragraphs rejected");
        MemoTable.Model ragged=MemoTable.create(2,2);ragged.cells.get(1).remove(0);rejects(()->MemoTable.encode(ragged),"public model corruption rejected");
        MemoTable.Model huge=MemoTable.create(20,2);for(int r=0;r<9;r++){MemoTable.setCell(huge,r,0,repeated('a',1000));MemoTable.setCell(huge,r,1,repeated('b',1000));}
        String hugeBefore=MemoTable.encode(huge);rejects(()->MemoTable.setCell(huge,10,0,repeated('c',1000)+"x"),"oversized cell no mutation");
        MemoTable.setCell(huge,9,0,repeated('c',1000));String nearCap=MemoTable.encode(huge);
        rejects(()->MemoTable.setCell(huge,9,1,repeated('d',1000)),"whole table body cap enforced before committing");eq(nearCap,MemoTable.encode(huge),"total-size rejection retains original table");
        check(hugeBefore.length()<nearCap.length(),"large valid table accepts bounded change");
        System.out.println("Memo table: "+checks+" checks passed");
    }
}
