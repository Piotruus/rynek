package pl.tworzyfilmy.auctionhouse;

import org.bukkit.inventory.ItemStack;
import java.util.UUID;

public record Auction(long id, UUID seller, String sellerName, double price, long expiresAt, ItemStack item) {}
