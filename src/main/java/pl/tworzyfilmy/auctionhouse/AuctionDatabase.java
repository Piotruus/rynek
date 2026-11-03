package pl.tworzyfilmy.auctionhouse;

import org.bukkit.inventory.ItemStack;
import java.io.IOException;
import java.sql.*;
import java.util.*;

final class AuctionDatabase {
    private final Connection connection;
    AuctionDatabase(java.io.File file) throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("PRAGMA journal_mode=WAL");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS auctions (id INTEGER PRIMARY KEY AUTOINCREMENT, seller TEXT NOT NULL, seller_name TEXT NOT NULL, price REAL NOT NULL, expires_at INTEGER NOT NULL, item TEXT NOT NULL)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS mailbox (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, item TEXT NOT NULL)");
        }
    }
    synchronized long add(UUID seller, String name, double price, long expires, ItemStack item) throws SQLException, IOException {
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO auctions(seller,seller_name,price,expires_at,item) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1,seller.toString()); ps.setString(2,name); ps.setDouble(3,price); ps.setLong(4,expires); ps.setString(5,ItemCodec.encode(item)); ps.executeUpdate();
            try (ResultSet rs=ps.getGeneratedKeys()) { if(rs.next()) return rs.getLong(1); }
        }
        try (Statement st=connection.createStatement(); ResultSet rs=st.executeQuery("SELECT last_insert_rowid()")) { return rs.next()?rs.getLong(1):-1; }
    }
    synchronized List<Auction> all(String search) throws SQLException {
        List<Auction> list=new ArrayList<>(); String sql="SELECT * FROM auctions ORDER BY id DESC";
        try(PreparedStatement ps=connection.prepareStatement(sql); ResultSet rs=ps.executeQuery()) {
            while(rs.next()) {
                Auction a=read(rs);
                if(search==null || matchesSearch(a.item(), search)) list.add(a);
            }
        } return list;
    }
    private static boolean matchesSearch(ItemStack item, String query) {
        String needle=normalize(query);
        if(needle.isBlank()) return true;
        StringBuilder haystack=new StringBuilder(item.getType().name().replace('_',' '));
        if(item.hasItemMeta()) {
            var meta=item.getItemMeta();
            if(meta.hasDisplayName()) haystack.append(' ').append(meta.getDisplayName());
            if(meta.hasLocalizedName()) haystack.append(' ').append(meta.getLocalizedName());
            if(meta.hasLore() && meta.getLore()!=null) for(String line:meta.getLore()) haystack.append(' ').append(line);
        }
        return normalize(haystack.toString()).contains(needle);
    }
    private static String normalize(String value) {
        String plain=org.bukkit.ChatColor.stripColor(value==null?"":value);
        return java.text.Normalizer.normalize(plain, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replace('_',' ').trim();
    }
    synchronized List<Auction> bySeller(UUID uuid) throws SQLException {
        List<Auction> list=new ArrayList<>(); try(PreparedStatement ps=connection.prepareStatement("SELECT * FROM auctions WHERE seller=? ORDER BY id DESC")) { ps.setString(1,uuid.toString()); try(ResultSet rs=ps.executeQuery()){while(rs.next())list.add(read(rs));} } return list;
    }
    private Auction read(ResultSet rs) throws SQLException {
        try { return new Auction(rs.getLong("id"),UUID.fromString(rs.getString("seller")),rs.getString("seller_name"),rs.getDouble("price"),rs.getLong("expires_at"),ItemCodec.decode(rs.getString("item"))); }
        catch(IOException|ClassNotFoundException|IllegalArgumentException ex){ throw new SQLException("Nie można odczytać przedmiotu aukcji",ex); }
    }
    synchronized boolean delete(long id) throws SQLException { try(PreparedStatement ps=connection.prepareStatement("DELETE FROM auctions WHERE id=?")){ps.setLong(1,id);return ps.executeUpdate()>0;} }
    synchronized List<Auction> expired(long now) throws SQLException { List<Auction> out=new ArrayList<>(); try(PreparedStatement ps=connection.prepareStatement("SELECT * FROM auctions WHERE expires_at<=?")){ps.setLong(1,now);try(ResultSet rs=ps.executeQuery()){while(rs.next())out.add(read(rs));}} return out; }
    synchronized void mail(UUID owner, ItemStack item) throws SQLException, IOException { try(PreparedStatement ps=connection.prepareStatement("INSERT INTO mailbox(owner,item) VALUES(?,?)")){ps.setString(1,owner.toString());ps.setString(2,ItemCodec.encode(item));ps.executeUpdate();} }
    synchronized List<ItemStack> claimMail(UUID owner) throws SQLException {
        List<ItemStack> items=new ArrayList<>();
        try { connection.setAutoCommit(false); try(PreparedStatement ps=connection.prepareStatement("SELECT id,item FROM mailbox WHERE owner=?")){ps.setString(1,owner.toString());try(ResultSet rs=ps.executeQuery()){while(rs.next()){items.add(ItemCodec.decode(rs.getString("item")));try(PreparedStatement del=connection.prepareStatement("DELETE FROM mailbox WHERE id=?")){del.setLong(1,rs.getLong("id"));del.executeUpdate();}}}} connection.commit(); }
        catch(Exception e){try{connection.rollback();}catch(SQLException ignored){} throw new SQLException("Nie można odebrać skrzynki",e);} finally {connection.setAutoCommit(true);} return items;
    }
    synchronized void close() throws SQLException { connection.close(); }
}
