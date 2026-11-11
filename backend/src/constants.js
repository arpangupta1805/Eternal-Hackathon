// Protocol constants. Must match docs/03-protocol.md.

export const PROTOCOL_VERSION = 1;

export const ORDER_VALIDITY_MS = 20 * 60 * 1000;
export const ACK_VALIDITY_MS = 60 * 60 * 1000;
export const STOCK_VALIDITY_MS = 30 * 60 * 1000;
export const STOCK_REISSUE_MS = 10 * 60 * 1000;
export const INITIAL_HOPS = 8;
export const MAX_PACKET_BYTES = 16384;
export const MAX_TRACE = 16;
export const DEFAULT_PRICE_TOLERANCE = 2000;
export const CLOCK_SKEW_MS = 2 * 60 * 1000;

export const ROLES = ['CUSTOMER', 'RIDER', 'STORE'];
export const ON_UNAVAILABLE = ['DROP', 'SUBSTITUTE', 'CANCEL_ORDER'];
