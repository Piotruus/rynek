package pl.tworzyfilmy.auctionhouse;

import net.milkbowl.vault.economy.Economy;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionHousePlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private AuctionDatabase database;
    private final Map<UUID,String> searchPrompts=new ConcurrentHashMap<>();
    private final Map<UUID,Integer> pages=new HashMap<>();
    private final Map<UUID,String> searches=new HashMap<>();
    private final Map<UUID,Long> confirmations=new HashMap<>();
    private final Map<UUID,Integer> sortModes=new HashMap<>();
    private final Map<UUID,String> filters=new HashMap<>();
    private final Map<UUID,String> categories=new HashMap<>();
    private static final String SORT="§6§lSORTOWANIE AUKCJI";
    private static final String FILTER="§6§lFILTROWANIE AUKCJI";
    private static final String MAIN="§7§lRYNEK";

    @Override public void onEnable(){
        saveDefaultConfig();
        if(!setupEconomy()){getLogger().severe("Nie znaleziono Vault lub pluginu ekonomii. AuctionHouse zostaje wyłączony.");getServer().getPluginManager().disablePlugin(this);return;}
        try{database=new AuctionDatabase(new java.io.File(getDataFolder(),"auctions.db"));}catch(Exception e){getLogger().severe("Błąd bazy SQLite: "+e.getMessage());getServer().getPluginManager().disablePlugin(this);return;}
        Objects.requireNonNull(getCommand("ah")).setExecutor(this); Objects.requireNonNull(getCommand("ah")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getScheduler().runTaskTimer(this,this::expireAuctions,1200L,1200L);
        getLogger().info("AuctionHouse uruchomiony.");
    }
    @Override public void onDisable(){if(database!=null)try{database.close();}catch(SQLException ignored){}}
    private boolean setupEconomy(){if(getServer().getPluginManager().getPlugin("Vault")==null)return false;RegisteredServiceProvider<Economy> rsp=getServer().getServicesManager().getRegistration(Economy.class);if(rsp==null)return false;economy=rsp.getProvider();return economy!=null;}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("Ta komenda jest tylko dla graczy.");return true;}
        if(args.length==0){help(p);return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "open" -> {pages.put(p.getUniqueId(),0);searches.remove(p.getUniqueId());openMarket(p);}
            case "sell" -> {if(args.length<2){msg(p,"§cUżycie: /ah sell <cena>");return true;}sell(p,args[1]);}
            case "search" -> {if(args.length<2){searchPrompts.put(p.getUniqueId(),"market");msg(p,"§eWpisz nazwę przedmiotu na czacie. Wpisz 'anuluj', aby przerwać.");return true;}searches.put(p.getUniqueId(),String.join(" ",Arrays.copyOfRange(args,1,args.length)));pages.put(p.getUniqueId(),0);openMarket(p);}
            case "my" -> openMyAuctions(p);
            case "collect" -> collect(p);
            case "help" -> help(p);
            default -> help(p);
        } return true;
    }
    private void help(Player p){msg(p,"§aDostępne komendy:");msg(p,"§f/ah open §7- otwiera rynek");msg(p,"§f/ah search <nazwa> §7- wyszukuje przedmioty");msg(p,"§f/ah sell <cena> §7- wystawia przedmiot z ręki");msg(p,"§f/ah my §7- twoje aukcje");msg(p,"§f/ah collect §7- odbiera przedmioty z poczty rynku");}
    private void sell(Player p,String raw){
        double price;try{price=Double.parseDouble(raw.replace(",","."));}catch(NumberFormatException e){msg(p,"§cPodaj prawidłową cenę.");return;}
        if(!Double.isFinite(price)||price<=0){msg(p,"§cCena musi być większa od 0.");return;}
        ItemStack hand=p.getInventory().getItemInMainHand();if(hand.getType()==Material.AIR){msg(p,"§cMusisz trzymać przedmiot w ręce.");return;}
        try{
            int limit=limit(p);int active=database.bySeller(p.getUniqueId()).size();if(active>=limit){msg(p,"§cOsiągnąłeś limit aukcji: §e"+limit+"§c.");return;}
            ItemStack listing=hand.clone();long expires=System.currentTimeMillis()+getConfig().getLong("auction-duration-hours",24)*3600000L;
            database.add(p.getUniqueId(),p.getName(),price,expires,listing);
            if(hand.getAmount()==listing.getAmount())p.getInventory().setItemInMainHand(null);else hand.setAmount(hand.getAmount()-listing.getAmount());
            String amount=format(price);p.sendTitle("§aTwój przedmiot trafił na", "§a§lRynek §aza §2"+amount, 5, 45, 10);
            TextComponent clickable=new TextComponent("§7Jeśli się pomyliłeś, §b§lKLIKNIJ TUTAJ §7aby go wycofać.");clickable.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/ah my"));p.spigot().sendMessage(clickable);
        }catch(Exception e){getLogger().severe("Nie udało się wystawić aukcji: "+e.getMessage());msg(p,"§cNie udało się wystawić przedmiotu. Nic nie zostało zabrane.");}
    }
    private int limit(Player p){String[] ranks={"goat","fire","mvip","svip","vip"};for(String rank:ranks){String perm=getConfig().getString("rank-permissions."+rank,"auctionhouse.limit."+rank);if(p.hasPermission(perm))return getConfig().getInt("limits."+rank,3);}return getConfig().getInt("limits.default",3);}
    private void openMarket(Player p){
        Inventory inv=Bukkit.createInventory(p,54,MAIN); List<Auction> list;
        try {
            list=database.all(searches.get(p.getUniqueId()));
            String category=categories.getOrDefault(p.getUniqueId(),"all");
            list.removeIf(a -> !matchesMainCategory(a,category));
            String filter=filters.getOrDefault(p.getUniqueId(),"all");
            if(filter.equals("affordable")) list.removeIf(a -> a.price()>economy.getBalance(p));
            int sort=sortModes.getOrDefault(p.getUniqueId(),0);
            if(sort==0) list.sort(Comparator.comparingDouble(Auction::price).reversed());
            else if(sort==1) list.sort(Comparator.comparingDouble(Auction::price));
            else if(sort==2) list.sort(Comparator.comparingLong(Auction::id));
            else list.sort(Comparator.comparingLong(Auction::id).reversed());
        } catch(SQLException ex){msg(p,"§cBłąd odczytu rynku.");return;}
        // 4 x 7 ofert: kolumna 0 to kategorie, kolumna 1 to separator.
        int[] offerSlots={2,3,4,5,6,7,8,11,12,13,14,15,16,17,20,21,22,23,24,25,26,29,30,31,32,33,34,35};
        int maxPage=Math.max(0,(list.size()-1)/offerSlots.length);
        int page=Math.max(0,Math.min(pages.getOrDefault(p.getUniqueId(),0),maxPage));
        pages.put(p.getUniqueId(),page);
        int start=page*offerSlots.length;
        for(int i=0;i<offerSlots.length && start+i<list.size();i++) inv.setItem(offerSlots[i],auctionIcon(list.get(start+i),false));
        // Kategorie po lewej stronie.
        String selected=categories.getOrDefault(p.getUniqueId(),"all");
        inv.setItem(0,categoryButton(Material.COMPASS,"§a§lWSZYSTKO","all",selected));
        inv.setItem(9,categoryButton(Material.IRON_CHESTPLATE,"§a§lUZBROJENIE","gear",selected));
        inv.setItem(18,categoryButton(Material.NETHER_STAR,"§6§lPREMIUM","premium",selected));
        inv.setItem(27,categoryButton(Material.DIAMOND_PICKAXE,"§a§lNARZĘDZIA","tools",selected));
        for(int slot:new int[]{1,10,19,28}) inv.setItem(slot,button(Material.BLACK_STAINED_GLASS_PANE,"§0",List.of()));
        // Poziomy separator nad dolnym paskiem.
        for(int slot=36;slot<45;slot++) inv.setItem(slot,button(Material.BLACK_STAINED_GLASS_PANE,"§0",List.of()));
        inv.setItem(45,button(Material.CHEST,"§e§lTWOJE AUKCJE",List.of("§7Kliknij, aby zarządzać aukcjami")));
        inv.setItem(46,button(Material.OAK_SIGN,"§e§lWYSZUKAJ",List.of("§7Kliknij i wpisz nazwę na czacie", "§8Lub użyj /ah search <nazwa>")));
        inv.setItem(47,button(Material.BLACK_STAINED_GLASS_PANE,"§0",List.of()));
        String filter=filters.getOrDefault(p.getUniqueId(),"all");
        inv.setItem(48,button(Material.HOPPER,"§e§lFILTROWANIE",List.of("§7Aktualnie: §f"+(filter.equals("all")?"WSZYSTKIE PRZEDMIOTY":"PRZEDMIOTY, NA KTÓRE CIĘ STAĆ"),"§eKliknij, aby zmienić")));
        inv.setItem(49,button(Material.COMPARATOR,"§e§lSORTOWANIE",List.of("§7Aktualnie: §f"+sortName(sortModes.getOrDefault(p.getUniqueId(),0)),"§eKliknij, aby zmienić")));
        inv.setItem(50,button(Material.BLACK_STAINED_GLASS_PANE,"§0",List.of()));
        inv.setItem(51,button(Material.BLACK_STAINED_GLASS_PANE,"§0",List.of()));
        if(page>0) inv.setItem(52,button(Material.ARROW,"§a§lPOPRZEDNIA STRONA",List.of("§7Strona "+(page+1))));
        if(start+offerSlots.length<list.size()) inv.setItem(53,button(Material.ARROW,"§a§lNASTĘPNA STRONA",List.of("§7Strona "+(page+1))));
        p.openInventory(inv);
    }
    private ItemStack categoryButton(Material material,String name,String key,String selected){return button(material,name,List.of(selected.equals(key)?"§aWybrano":"§7Kliknij, aby wyświetlić"));}
    private boolean matchesMainCategory(Auction a,String key){
        Material m=a.item().getType(); String n=m.name();
        return switch(key){
            case "gear" -> (isWeapon(n) || isArmor(n)) && !isPremium(a.item());
            case "tools" -> isTool(n) && !isPremium(a.item());
            case "premium" -> isPremium(a.item());
            default -> true;
        };
    }
    private boolean isWeapon(String n){return n.endsWith("_SWORD")||n.equals("BOW")||n.equals("CROSSBOW")||n.equals("TRIDENT")||n.equals("MACE");}
    private boolean isArmor(String n){return n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS")||n.equals("SHIELD")||n.equals("ELYTRA")||n.equals("TURTLE_HELMET");}
    private boolean isTool(String n){return n.endsWith("_PICKAXE")||n.endsWith("_AXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.equals("SHEARS")||n.equals("FISHING_ROD")||n.equals("FLINT_AND_STEEL")||n.equals("BRUSH");}
    private boolean isPremium(ItemStack item){
        Material m=item.getType(); String n=m.name();
        if(n.startsWith("NETHERITE_")||n.equals("ELYTRA")||n.equals("TOTEM_OF_UNDYING")||n.equals("DRAGON_EGG")||n.equals("BEACON")) return true;
        if(!item.hasItemMeta()) return false;
        ItemMeta meta=item.getItemMeta();
        return meta.hasEnchants() || meta.hasCustomModelData();
    }
    private void openMyAuctions(Player p){Inventory inv=Bukkit.createInventory(p,54,"§2§lTWOJE AUKCJE");try{List<Auction> list=database.bySeller(p.getUniqueId());for(int i=0;i<Math.min(45,list.size());i++)inv.setItem(i,auctionIcon(list.get(i),true));inv.setItem(49,button(Material.ARROW,"§cPowrót na rynek",List.of("§7Kliknij, aby wrócić")));p.openInventory(inv);}catch(SQLException e){msg(p,"§cBłąd odczytu aukcji.");}}
    private ItemStack auctionIcon(Auction a,boolean own){ItemStack item=a.item().clone();ItemMeta meta=item.getItemMeta();List<String> lore=meta.hasLore()?new ArrayList<>(meta.getLore()):new ArrayList<>();lore.add(" ");lore.add("§7Sprzedawca: §f"+a.sellerName());lore.add("§7Cena: §a"+format(a.price()));lore.add("§7Wygasa za: §e"+timeLeft(a.expiresAt()));lore.add(" ");lore.add(own?"§cKliknij, aby wycofać aukcję":"§aKliknij, aby kupić");lore.add("§8ID: "+a.id());meta.setLore(lore);item.setItemMeta(meta);return item;}
    private ItemStack button(Material material,String name,List<String> lore){return button(material,name,lore,true);}
    private ItemStack button(Material material,String name,List<String> lore,boolean enabled){ItemStack item=new ItemStack(material);if(!enabled)item.setType(Material.GRAY_STAINED_GLASS_PANE);ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);meta.setLore(lore);item.setItemMeta(meta);return item;}

    @EventHandler public void onClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p))return; String title=e.getView().getTitle();
        if(!title.equals(MAIN)&&!title.equals("§2§lTWOJE AUKCJE")&&!title.equals("§6§lPOTWIERDŹ ZAKUP"))return;
        e.setCancelled(true); if(e.getClickedInventory()==null||e.getClickedInventory()!=e.getView().getTopInventory())return; int slot=e.getRawSlot();
        if(title.equals(MAIN)){
            if(slot==0||slot==9||slot==18||slot==27){categories.put(p.getUniqueId(),switch(slot){case 9->"gear";case 18->"premium";case 27->"tools";default->"all";});pages.put(p.getUniqueId(),0);openMarket(p);return;}
            if(slot==45){openMyAuctions(p);return;}
            if(slot==46){searchPrompts.put(p.getUniqueId(),"market");p.closeInventory();msg(p,"§eWpisz nazwę przedmiotu na czacie (lub 'anuluj'):");return;}
            if(slot==48){filters.put(p.getUniqueId(),filters.getOrDefault(p.getUniqueId(),"all").equals("all")?"affordable":"all");pages.put(p.getUniqueId(),0);openMarket(p);return;}
            if(slot==49){sortModes.put(p.getUniqueId(),(sortModes.getOrDefault(p.getUniqueId(),0)+1)%4);pages.put(p.getUniqueId(),0);openMarket(p);return;}
            if(slot==52&&pages.getOrDefault(p.getUniqueId(),0)>0){pages.put(p.getUniqueId(),pages.get(p.getUniqueId())-1);openMarket(p);return;}
            if(slot==53){pages.put(p.getUniqueId(),pages.getOrDefault(p.getUniqueId(),0)+1);openMarket(p);return;}
            ItemStack clicked=e.getCurrentItem();if(clicked!=null&&clicked.hasItemMeta()&&clicked.getItemMeta().hasLore()){long id=parseId(clicked);if(id>0)confirmBuy(p,id);}
        }else if(title.equals("§2§lTWOJE AUKCJE")){if(slot==49){openMarket(p);return;}ItemStack clicked=e.getCurrentItem();if(clicked==null)return;long id=parseId(clicked);if(id>0)cancelAuction(p,id);}
        else if(title.equals("§6§lPOTWIERDŹ ZAKUP")){Long id=confirmations.get(p.getUniqueId());if(id==null)return;if(slot==11){confirmations.remove(p.getUniqueId());buy(p,id);}else if(slot==15){confirmations.remove(p.getUniqueId());openMarket(p);}}
    }
    private String sortName(int mode){return switch(mode){case 0->"od najwyższej ceny";case 1->"od najniższej ceny";case 2->"od najstarszej";default->"od najnowszej";};}
    private String filterName(String key){return switch(key){case "weapons"->"broń";case "armor"->"zbroje";case "tools"->"narzędzia";case "blocks"->"bloki";case "ores"->"surowce i rudy";case "food"->"jedzenie";case "farming"->"rolnictwo";case "potions"->"mikstury";case "books"->"zaklęte książki";case "other"->"pozostałe";default->"wszystkie";};}
    private boolean matchesCategory(Material m,String key){String n=m.name();return switch(key){
        case "weapons"->n.endsWith("_SWORD")||n.equals("BOW")||n.equals("CROSSBOW")||n.equals("TRIDENT");
        case "armor"->n.endsWith("_HELMET")||n.endsWith("_CHESTPLATE")||n.endsWith("_LEGGINGS")||n.endsWith("_BOOTS")||n.equals("ELYTRA")||n.equals("SHIELD");
        case "tools"->n.endsWith("_PICKAXE")||n.endsWith("_AXE")||n.endsWith("_SHOVEL")||n.endsWith("_HOE")||n.equals("SHEARS")||n.equals("FISHING_ROD");
        case "blocks"->n.endsWith("_BLOCK")||n.endsWith("_PLANKS")||n.endsWith("_LOG")||n.endsWith("_WOOL")||n.endsWith("_STONE")||n.endsWith("_BRICKS")||n.endsWith("_GLASS")||n.endsWith("_CONCRETE")||n.endsWith("_TERRACOTTA");
        case "ores"->n.contains("DIAMOND")||n.contains("EMERALD")||n.contains("IRON_INGOT")||n.contains("GOLD_INGOT")||n.contains("COPPER_INGOT")||n.contains("LAPIS")||n.contains("REDSTONE")||n.contains("QUARTZ")||n.contains("NETHERITE")||n.endsWith("_ORE");
        case "food"->n.contains("APPLE")||n.contains("BREAD")||n.contains("BEEF")||n.contains("PORKCHOP")||n.contains("CHICKEN")||n.contains("MUTTON")||n.contains("RABBIT")||n.contains("COD")||n.contains("SALMON")||n.contains("POTATO")||n.contains("CARROT")||n.contains("COOKIE")||n.contains("PIE")||n.contains("MELON")||n.contains("BERRY")||n.contains("STEW");
        case "farming"->n.contains("SEEDS")||n.contains("WHEAT")||n.contains("SUGAR_CANE")||n.contains("PUMPKIN")||n.contains("MELON")||n.contains("SAPLING")||n.contains("FUNGUS")||n.contains("MUSHROOM")||n.contains("HAY_BLOCK")||n.contains("COCOA");
        case "potions"->n.equals("POTION")||n.equals("SPLASH_POTION")||n.equals("LINGERING_POTION")||n.equals("TIPPED_ARROW");
        case "books"->n.equals("ENCHANTED_BOOK");
        case "other"->!matchesCategory(m,"weapons")&&!matchesCategory(m,"armor")&&!matchesCategory(m,"tools")&&!matchesCategory(m,"blocks")&&!matchesCategory(m,"ores")&&!matchesCategory(m,"food")&&!matchesCategory(m,"farming")&&!matchesCategory(m,"potions")&&!matchesCategory(m,"books");
        default->true;};}
    private long parseId(ItemStack item){try{for(String line:item.getItemMeta().getLore())if(line.startsWith("§8ID: "))return Long.parseLong(line.substring(6));}catch(Exception ignored){}return -1;}
    private void confirmBuy(Player p,long id){try{Auction a=database.all(null).stream().filter(x->x.id()==id).findFirst().orElse(null);if(a==null){msg(p,"§cTa aukcja już nie istnieje.");openMarket(p);return;}if(a.seller().equals(p.getUniqueId())){msg(p,"§cNie możesz kupić własnej aukcji.");return;}Inventory inv=Bukkit.createInventory(p,27,"§6§lPOTWIERDŹ ZAKUP");inv.setItem(13,auctionIcon(a,false));inv.setItem(11,button(Material.LIME_WOOL,"§aPOTWIERDŹ ZAKUP",List.of("§7Cena: §e"+format(a.price()))));inv.setItem(15,button(Material.RED_WOOL,"§cANULUJ",List.of("§7Wróć na rynek")));confirmations.put(p.getUniqueId(),id);p.openInventory(inv);}catch(SQLException e){msg(p,"§cBłąd rynku.");}}
    private void buy(Player buyer,long id){try{Auction a=database.all(null).stream().filter(x->x.id()==id).findFirst().orElse(null);if(a==null||a.expiresAt()<=System.currentTimeMillis()){msg(buyer,"§cTa aukcja wygasła lub została kupiona.");openMarket(buyer);return;}if(a.seller().equals(buyer.getUniqueId())){msg(buyer,"§cNie możesz kupić własnej aukcji.");return;}if(economy.getBalance(buyer)<a.price()){msg(buyer,"§cNie masz wystarczająco pieniędzy.");return;}if(!database.delete(id)){msg(buyer,"§cKtoś właśnie kupił tę aukcję.");return;}
        var withdraw=economy.withdrawPlayer(buyer,a.price());if(!withdraw.transactionSuccess()){database.add(a.seller(),a.sellerName(),a.price(),a.expiresAt(),a.item());msg(buyer,"§cPłatność nie powiodła się.");return;}
        double fee=a.price()*getConfig().getDouble("commission-percent",3.0)/100.0;double payout=a.price()-fee;var deposit=economy.depositPlayer(Bukkit.getOfflinePlayer(a.seller()),payout);if(!deposit.transactionSuccess()){economy.depositPlayer(buyer,a.price());database.add(a.seller(),a.sellerName(),a.price(),a.expiresAt(),a.item());msg(buyer,"§cNie udało się przekazać płatności. Transakcja cofnięta.");return;}
        HashMap<Integer,ItemStack> leftovers=buyer.getInventory().addItem(a.item().clone());if(!leftovers.isEmpty())for(ItemStack item:leftovers.values())database.mail(buyer.getUniqueId(),item);
        Player seller=Bukkit.getPlayer(a.seller());if(seller!=null)seller.sendTitle("§aTwój przedmiot został sprzedany za", "§2"+format(a.price()), 5, 45, 10);msg(buyer,"§aZakupiono przedmiot za §e"+format(a.price())+"§a. Sprzedawca otrzymał §e"+format(payout)+"§a po prowizji 3%.");openMarket(buyer);
    }catch(Exception e){getLogger().severe("Błąd zakupu: "+e.getMessage());msg(buyer,"§cWystąpił błąd zakupu. Skontaktuj się z administracją.");}}
    private void cancelAuction(Player p,long id){try{Auction a=database.all(null).stream().filter(x->x.id()==id&&x.seller().equals(p.getUniqueId())).findFirst().orElse(null);if(a==null){msg(p,"§cNie znaleziono twojej aukcji.");return;}if(!database.delete(id))return;HashMap<Integer,ItemStack> left=p.getInventory().addItem(a.item().clone());for(ItemStack item:left.values())database.mail(p.getUniqueId(),item);msg(p,"§aAukcja została wycofana. Przedmiot zwrócono.");openMyAuctions(p);}catch(Exception e){msg(p,"§cNie udało się wycofać aukcji.");}}
    private void expireAuctions(){try{for(Auction a:database.expired(System.currentTimeMillis())){if(!database.delete(a.id()))continue;database.mail(a.seller(),a.item());Player seller=Bukkit.getPlayer(a.seller());if(seller!=null)seller.sendTitle("§7Twój przedmiot", "§7§lwygasł §7sprawdź rynek", 5, 45, 10);}}catch(Exception e){getLogger().warning("Błąd wygaszania aukcji: "+e.getMessage());}}
    private void collect(Player p){try{List<ItemStack> items=database.claimMail(p.getUniqueId());if(items.isEmpty()){msg(p,"§eNie masz przedmiotów do odebrania.");return;}for(ItemStack item:items){HashMap<Integer,ItemStack> left=p.getInventory().addItem(item);for(ItemStack overflow:left.values())database.mail(p.getUniqueId(),overflow);}msg(p,"§aOdebrano przedmioty z rynku.");}catch(Exception ex){msg(p,"§cNie udało się odebrać przedmiotów.");}}
    @EventHandler public void onJoin(PlayerJoinEvent e){try{List<ItemStack> items=database.claimMail(e.getPlayer().getUniqueId());if(!items.isEmpty()){for(ItemStack item:items){HashMap<Integer,ItemStack> left=e.getPlayer().getInventory().addItem(item);for(ItemStack overflow:left.values())e.getPlayer().getWorld().dropItemNaturally(e.getPlayer().getLocation(),overflow);}msg(e.getPlayer(),"§aOdebrano przedmioty z rynku.");}}catch(Exception ex){getLogger().warning("Nie można odebrać skrzynki gracza: "+ex.getMessage());}}
    @EventHandler public void onChat(AsyncPlayerChatEvent e){String mode=searchPrompts.remove(e.getPlayer().getUniqueId());if(mode==null)return;e.setCancelled(true);String query=e.getMessage().trim();Bukkit.getScheduler().runTask(this,()->{if(query.equalsIgnoreCase("anuluj")){msg(e.getPlayer(),"§cWyszukiwanie anulowane.");return;}searches.put(e.getPlayer().getUniqueId(),query);pages.put(e.getPlayer().getUniqueId(),0);openMarket(e.getPlayer());});}
    private String format(double amount){DecimalFormatSymbols symbols=DecimalFormatSymbols.getInstance(Locale.US);if(amount>=1_000_000_000)return new DecimalFormat("0.#",symbols).format(amount/1_000_000_000)+"b$";if(amount>=1_000_000)return new DecimalFormat("0.#",symbols).format(amount/1_000_000)+"m$";if(amount>=1_000)return new DecimalFormat("0.#",symbols).format(amount/1_000)+"k$";return new DecimalFormat("#,##0.##",symbols).format(amount)+"$";}
    private String timeLeft(long expiry){long sec=Math.max(0,(expiry-System.currentTimeMillis())/1000);long d=sec/86400,h=(sec%86400)/3600,m=(sec%3600)/60,s=sec%60;if(d>0)return d+"d "+h+"h "+m+"m";if(h>0)return h+"h "+m+"m "+s+"s";return m+"m "+s+"s";}
    private void msg(Player p,String message){p.sendMessage(getConfig().getString("messages.prefix","§8[§aRynek§8] ").replace('&','§')+message);}
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){if(args.length==1)return Arrays.asList("open","search","sell","my","collect","help").stream().filter(x->x.startsWith(args[0].toLowerCase())).toList();return Collections.emptyList();}
}
