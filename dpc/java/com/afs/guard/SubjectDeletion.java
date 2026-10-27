package com.afs.guard;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.CheckBox;

/** Deleting a subject is an explicit move to the recoverable trash. */
final class SubjectDeletion {
    static void confirm(Activity activity,Ui ui,String name,String contents,Runnable action) {
        CheckBox checked=new CheckBox(activity);checked.setText("我确认将「"+name+"」移入回收站");
        checked.setTextColor(ui.text());checked.setPadding(ui.dp(20),ui.dp(8),ui.dp(20),ui.dp(12));
        AlertDialog dialog=ThemeStore.dialog(activity).setTitle("删除学科？")
            .setMessage("「"+name+"」包含 "+contents+"。\n删除后从学科列表移除，内容和学习记录保留，可以撤回或在回收站恢复。")
            .setView(checked).setNegativeButton("取消",null).setPositiveButton("移入回收站",(d,w)->action.run()).create();
        dialog.setOnShowListener(d->{dialog.getButton(-1).setEnabled(false);checked.setOnCheckedChangeListener((v,value)->dialog.getButton(-1).setEnabled(value));});
        dialog.show();
        ThemeStore.styleDialog(dialog);
    }
}
