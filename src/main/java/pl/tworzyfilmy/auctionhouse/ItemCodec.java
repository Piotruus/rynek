package pl.tworzyfilmy.auctionhouse;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.io.*;
import java.util.Base64;

final class ItemCodec {
    static String encode(ItemStack item) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) { out.writeObject(item); }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }
    static ItemStack decode(String value) throws IOException, ClassNotFoundException {
        byte[] bytes = Base64.getDecoder().decode(value);
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes))) {
            return (ItemStack) in.readObject();
        }
    }
}
