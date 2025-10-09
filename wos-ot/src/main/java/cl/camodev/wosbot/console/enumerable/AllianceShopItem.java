package cl.camodev.wosbot.console.enumerable;

/**
 * Enum defining the available items in the Alliance Shop.
 * Modify this enum to change the items available in the shop.
 */
public enum AllianceShopItem implements PrioritizableItem {
    MYTHIC_HERO_SHARDS("Mythic Hero Shards"),
    PET_FOOD("Pet Food"),
    PET_CHEST("Pet Chest"),
    TRANSFER_PASS("Transfer Pass"),;

    private final String displayName;

    AllianceShopItem(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Gets the enum name as identifier (used for matching with saved data)
     */
    @Override
    public String getIdentifier() {
        return this.name();
    }

    @Override
    public String toString() {
        return displayName;
    }
}
