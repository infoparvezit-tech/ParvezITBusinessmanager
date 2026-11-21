package com.parvezit.businessmanager;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;

public class DB extends SQLiteOpenHelper {
    public DB(Context c){super(c,"parvezit.db",null,3);}
    public void onCreate(SQLiteDatabase d){
        d.execSQL("CREATE TABLE transactions(id INTEGER PRIMARY KEY AUTOINCREMENT,type TEXT NOT NULL,title TEXT NOT NULL,amount REAL NOT NULL,note TEXT,date TEXT DEFAULT CURRENT_TIMESTAMP)");
        d.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,category TEXT,qty INTEGER DEFAULT 0,buy REAL DEFAULT 0,sell REAL DEFAULT 0)");
        d.execSQL("CREATE TABLE customers(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,phone TEXT,due REAL DEFAULT 0)");
        d.execSQL("CREATE TABLE invoices(id INTEGER PRIMARY KEY AUTOINCREMENT,customer TEXT,phone TEXT,total REAL,paid REAL DEFAULT 0,date TEXT DEFAULT CURRENT_TIMESTAMP)");
        d.execSQL("CREATE TABLE invoice_items(id INTEGER PRIMARY KEY AUTOINCREMENT,invoice_id INTEGER,product_id INTEGER,product_name TEXT,qty INTEGER,price REAL,line_total REAL)");
        d.execSQL("CREATE TABLE suppliers(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,phone TEXT,due REAL DEFAULT 0)");
        d.execSQL("CREATE TABLE purchases(id INTEGER PRIMARY KEY AUTOINCREMENT,supplier TEXT,product_id INTEGER,product_name TEXT,qty INTEGER,cost REAL,total REAL,date TEXT DEFAULT CURRENT_TIMESTAMP)");
        d.execSQL("CREATE TABLE customer_payments(id INTEGER PRIMARY KEY AUTOINCREMENT,customer TEXT,amount REAL,note TEXT,date TEXT DEFAULT CURRENT_TIMESTAMP)");
    }
    public void onUpgrade(SQLiteDatabase d,int oldV,int newV){
        if(oldV<2){
            d.execSQL("CREATE TABLE IF NOT EXISTS invoices(id INTEGER PRIMARY KEY AUTOINCREMENT,customer TEXT,phone TEXT,total REAL,paid REAL DEFAULT 0,date TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE TABLE IF NOT EXISTS invoice_items(id INTEGER PRIMARY KEY AUTOINCREMENT,invoice_id INTEGER,product_id INTEGER,product_name TEXT,qty INTEGER,price REAL,line_total REAL)");
        }
        if(oldV<3){
            try{d.execSQL("ALTER TABLE invoices ADD COLUMN paid REAL DEFAULT 0");}catch(Exception ignored){}
            d.execSQL("CREATE TABLE IF NOT EXISTS suppliers(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,phone TEXT,due REAL DEFAULT 0)");
            d.execSQL("CREATE TABLE IF NOT EXISTS purchases(id INTEGER PRIMARY KEY AUTOINCREMENT,supplier TEXT,product_id INTEGER,product_name TEXT,qty INTEGER,cost REAL,total REAL,date TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE TABLE IF NOT EXISTS customer_payments(id INTEGER PRIMARY KEY AUTOINCREMENT,customer TEXT,amount REAL,note TEXT,date TEXT DEFAULT CURRENT_TIMESTAMP)");
            d.execSQL("CREATE TABLE IF NOT EXISTS customers(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE,phone TEXT,due REAL DEFAULT 0)");
        }
    }
    public boolean addTransaction(String type,String title,double amount,String note){
        ContentValues v=new ContentValues();v.put("type",type);v.put("title",title);v.put("amount",amount);v.put("note",note);
        return getWritableDatabase().insert("transactions",null,v)>0;
    }
    public double total(String type){Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type=?",new String[]{type});double n=0;if(c.moveToFirst())n=c.getDouble(0);c.close();return n;}
    public Cursor recent(){return getReadableDatabase().rawQuery("SELECT id,type,title,amount,date FROM transactions ORDER BY id DESC LIMIT 100",null);}
    public boolean addProduct(String n,String cat,int q,double buy,double sell){ContentValues v=new ContentValues();v.put("name",n);v.put("category",cat);v.put("qty",q);v.put("buy",buy);v.put("sell",sell);return getWritableDatabase().insert("products",null,v)>0;}
    public Cursor products(){return getReadableDatabase().rawQuery("SELECT id,name,category,qty,buy,sell FROM products ORDER BY name",null);}
    public Cursor customers(){return getReadableDatabase().rawQuery("SELECT name,phone,due FROM customers ORDER BY name",null);}
    public boolean addCustomer(String n,String p,double due){ContentValues v=new ContentValues();v.put("name",n);v.put("phone",p);v.put("due",due);return getWritableDatabase().insertWithOnConflict("customers",null,v,SQLiteDatabase.CONFLICT_REPLACE)>0;}
    public Cursor suppliers(){return getReadableDatabase().rawQuery("SELECT name,phone,due FROM suppliers ORDER BY name",null);}
    public boolean addSupplier(String n,String p){ContentValues v=new ContentValues();v.put("name",n);v.put("phone",p);return getWritableDatabase().insert("suppliers",null,v)>0;}
    public long createInvoice(String customer,String phone,int productId,String productName,int qty,double price,double paid){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{
            Cursor c=d.rawQuery("SELECT qty FROM products WHERE id=?",new String[]{""+productId});
            if(!c.moveToFirst()||c.getInt(0)<qty){c.close();return -1;}c.close();
            double total=qty*price;if(paid<0||paid>total)return -1;
            ContentValues iv=new ContentValues();iv.put("customer",customer);iv.put("phone",phone);iv.put("total",total);iv.put("paid",paid);
            long id=d.insertOrThrow("invoices",null,iv);
            ContentValues item=new ContentValues();item.put("invoice_id",id);item.put("product_id",productId);item.put("product_name",productName);item.put("qty",qty);item.put("price",price);item.put("line_total",total);d.insertOrThrow("invoice_items",null,item);
            d.execSQL("UPDATE products SET qty=qty-? WHERE id=?",new Object[]{qty,productId});
            ContentValues cu=new ContentValues();cu.put("name",customer);cu.put("phone",phone);cu.put("due",total-paid);d.insertWithOnConflict("customers",null,cu,SQLiteDatabase.CONFLICT_IGNORE);
            d.execSQL("UPDATE customers SET due=due+? WHERE name=?",new Object[]{total-paid,customer});
            if(paid>0){ContentValues pay=new ContentValues();pay.put("customer",customer);pay.put("amount",paid);pay.put("note","Invoice #"+id+" initial payment");d.insert("customer_payments",null,pay);}
            ContentValues tr=new ContentValues();tr.put("type","IN");tr.put("title","Invoice #"+id+" - "+productName);tr.put("amount",total);tr.put("note","Customer: "+customer);d.insertOrThrow("transactions",null,tr);
            d.setTransactionSuccessful();return id;
        }catch(Exception e){return -1;}finally{d.endTransaction();}
    }
    public Cursor invoice(long id){return getReadableDatabase().rawQuery("SELECT i.id,i.customer,i.phone,i.total,i.date,x.product_name,x.qty,x.price,x.line_total,i.paid FROM invoices i JOIN invoice_items x ON x.invoice_id=i.id WHERE i.id=?",new String[]{""+id});}
    public boolean collectDue(String customer,double amount,String note){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{Cursor c=d.rawQuery("SELECT due FROM customers WHERE name=?",new String[]{customer});if(!c.moveToFirst()||amount<=0||amount>c.getDouble(0)){c.close();return false;}c.close();
            d.execSQL("UPDATE customers SET due=due-? WHERE name=?",new Object[]{amount,customer});
            ContentValues p=new ContentValues();p.put("customer",customer);p.put("amount",amount);p.put("note",note);d.insert("customer_payments",null,p);
            ContentValues t=new ContentValues();t.put("type","IN");t.put("title","Due collection - "+customer);t.put("amount",amount);t.put("note",note);d.insert("transactions",null,t);
            d.setTransactionSuccessful();return true;
        }finally{d.endTransaction();}
    }
    public boolean addPurchase(String supplier,int productId,String productName,int qty,double cost){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try{if(qty<=0||cost<0)return false;double total=qty*cost;d.execSQL("UPDATE products SET qty=qty+?,buy=? WHERE id=?",new Object[]{qty,cost,productId});
            ContentValues p=new ContentValues();p.put("supplier",supplier);p.put("product_id",productId);p.put("product_name",productName);p.put("qty",qty);p.put("cost",cost);p.put("total",total);d.insert("purchases",null,p);
            ContentValues t=new ContentValues();t.put("type","OUT");t.put("title","Purchase - "+productName);t.put("amount",total);t.put("note","Supplier: "+supplier);d.insert("transactions",null,t);
            d.setTransactionSuccessful();return true;
        }finally{d.endTransaction();}
    }
    public double sum(String sql){Cursor c=getReadableDatabase().rawQuery(sql,null);double x=0;if(c.moveToFirst())x=c.getDouble(0);c.close();return x;}
}