package com.v2ray.ang.data

/** Triggers that keep ProfileItem.groupSortOrder aligned with subscriptions.sortOrder. */
internal val GROUP_ORDER_TRIGGERS = listOf(
    """
    CREATE TRIGGER IF NOT EXISTS profiles_group_order_insert
    AFTER INSERT ON profiles
    WHEN NEW.groupSortOrder <> IFNULL(
        (SELECT sortOrder FROM subscriptions WHERE guid = NEW.subscriptionId),
        9223372036854775807)
    BEGIN
      UPDATE profiles SET groupSortOrder = IFNULL(
        (SELECT sortOrder FROM subscriptions WHERE guid = NEW.subscriptionId),
        9223372036854775807)
      WHERE guid = NEW.guid;
    END
    """,

    """
    CREATE TRIGGER IF NOT EXISTS profiles_group_order_regroup
    AFTER UPDATE OF subscriptionId ON profiles
    WHEN NEW.subscriptionId <> OLD.subscriptionId
    BEGIN
      UPDATE profiles SET groupSortOrder = IFNULL(
        (SELECT sortOrder FROM subscriptions WHERE guid = NEW.subscriptionId),
        9223372036854775807)
      WHERE guid = NEW.guid;
    END
    """,

    """
    CREATE TRIGGER IF NOT EXISTS profiles_group_order_fix
    AFTER UPDATE OF groupSortOrder ON profiles
    WHEN NEW.groupSortOrder <> IFNULL(
        (SELECT sortOrder FROM subscriptions WHERE guid = NEW.subscriptionId),
        9223372036854775807)
    BEGIN
      UPDATE profiles SET groupSortOrder = IFNULL(
        (SELECT sortOrder FROM subscriptions WHERE guid = NEW.subscriptionId),
        9223372036854775807)
      WHERE guid = NEW.guid;
    END
    """,

    """
    CREATE TRIGGER IF NOT EXISTS subscriptions_group_order_cascade
    AFTER UPDATE OF sortOrder ON subscriptions
    WHEN NEW.sortOrder <> OLD.sortOrder
    BEGIN
      UPDATE profiles SET groupSortOrder = NEW.sortOrder WHERE subscriptionId = NEW.guid;
    END
    """,

    """
    CREATE TRIGGER IF NOT EXISTS subscriptions_group_order_adopt
    AFTER INSERT ON subscriptions
    BEGIN
      UPDATE profiles SET groupSortOrder = NEW.sortOrder WHERE subscriptionId = NEW.guid;
    END
    """,

    """
    CREATE TRIGGER IF NOT EXISTS subscriptions_group_order_orphan
    AFTER DELETE ON subscriptions
    BEGIN
      UPDATE profiles SET groupSortOrder = 9223372036854775807 WHERE subscriptionId = OLD.guid;
    END
    """
)
