/// Minimal SMILE (binary JSON) parser for ES/OS snapshot shard metadata.
/// Only handles the subset needed: objects, arrays, strings, integers, longs, binary.
/// SMILE spec: https://github.com/FasterXML/smile-format-specification
use std::io;

#[derive(Debug, Clone)]
pub enum SmileValue {
    Null,
    Bool(bool),
    Int(i64),
    Float(f64),
    String(String),
    Binary(Vec<u8>),
    Array(Vec<SmileValue>),
    Object(Vec<(String, SmileValue)>),
}

impl SmileValue {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            SmileValue::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match self {
            SmileValue::Int(n) => Some(*n),
            _ => None,
        }
    }

    pub fn as_array(&self) -> Option<&[SmileValue]> {
        match self {
            SmileValue::Array(a) => Some(a),
            _ => None,
        }
    }

    pub fn get(&self, key: &str) -> Option<&SmileValue> {
        match self {
            SmileValue::Object(pairs) => pairs.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_bytes(&self) -> Option<&[u8]> {
        match self {
            SmileValue::Binary(b) => Some(b),
            _ => None,
        }
    }
}

pub struct SmileParser<'a> {
    data: &'a [u8],
    pos: usize,
    /// Shared string references for keys
    shared_keys: Vec<String>,
    /// Shared string references for values
    shared_values: Vec<String>,
    /// Whether shared key names are enabled (from header)
    shared_keys_enabled: bool,
    /// Whether shared string values are enabled (from header)
    shared_values_enabled: bool,
    /// Whether raw binary is enabled (from header)
    raw_binary: bool,
}

impl<'a> SmileParser<'a> {
    pub fn parse(data: &'a [u8]) -> io::Result<SmileValue> {
        let mut parser = SmileParser {
            data,
            pos: 0,
            shared_keys: Vec::new(),
            shared_values: Vec::new(),
            shared_keys_enabled: false,
            shared_values_enabled: false,
            raw_binary: false,
        };
        parser.read_header()?;
        parser.read_value()
    }

    fn read_byte(&mut self) -> io::Result<u8> {
        if self.pos >= self.data.len() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of SMILE data"));
        }
        let b = self.data[self.pos];
        self.pos += 1;
        Ok(b)
    }

    fn peek_byte(&self) -> io::Result<u8> {
        if self.pos >= self.data.len() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of SMILE data"));
        }
        Ok(self.data[self.pos])
    }

    fn read_bytes(&mut self, n: usize) -> io::Result<&'a [u8]> {
        if self.pos + n > self.data.len() {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected end of SMILE data"));
        }
        let slice = &self.data[self.pos..self.pos + n];
        self.pos += n;
        Ok(slice)
    }

    fn read_header(&mut self) -> io::Result<()> {
        let h = self.read_bytes(3)?;
        if h != [0x3A, 0x29, 0x0A] {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "invalid SMILE header"));
        }
        let version = self.read_byte()?;
        // Feature flags in bits 0-2 (from Jackson SmileConstants):
        // Bit 0 (0x01): HEADER_BIT_HAS_SHARED_NAMES (shared key names)
        // Bit 1 (0x02): HEADER_BIT_HAS_SHARED_STRING_VALUES
        // Bit 2 (0x04): HEADER_BIT_HAS_RAW_BINARY
        self.shared_keys_enabled = version & 0x01 != 0;
        self.shared_values_enabled = version & 0x02 != 0;
        self.raw_binary = version & 0x04 != 0;
        Ok(())
    }

    /// Read unsigned VInt (big-endian, MSB continuation bit).
    /// Bytes with bit 7 clear (< 0x80) are continuation bytes (7 data bits).
    /// The final byte has bit 7 set (>= 0x80) and contributes 6 data bits.
    fn read_unsigned_vint(&mut self) -> io::Result<u64> {
        let mut value: u64 = 0;
        loop {
            let b = self.read_byte()?;
            if b < 0x80 {
                // Continuation byte: 7 data bits
                value = (value << 7) | (b as u64);
            } else {
                // Last byte: 6 data bits
                value = (value << 6) | ((b & 0x3F) as u64);
                return Ok(value);
            }
        }
    }

    /// Read signed VInt (unsigned VInt + zigzag decode).
    fn read_signed_vint(&mut self) -> io::Result<i64> {
        let raw = self.read_unsigned_vint()?;
        Ok(zigzag_decode(raw))
    }

    fn read_safe_utf8(&mut self, len: usize) -> io::Result<String> {
        let bytes = self.read_bytes(len)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))
    }

    fn read_long_string(&mut self) -> io::Result<String> {
        // Read until 0xFC (end marker)
        let start = self.pos;
        while self.pos < self.data.len() && self.data[self.pos] != 0xFC {
            self.pos += 1;
        }
        let s = String::from_utf8(self.data[start..self.pos].to_vec())
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
        if self.pos < self.data.len() {
            self.pos += 1; // skip 0xFC end marker
        }
        Ok(s)
    }

    fn read_key(&mut self) -> io::Result<Option<String>> {
        let b = self.read_byte()?;
        match b {
            // Object end marker
            0xFB => Ok(None),

            // Short shared key reference (1 byte)
            0x40..=0x7F if self.shared_keys_enabled => {
                let idx = (b - 0x40) as usize;
                if idx < self.shared_keys.len() {
                    Ok(Some(self.shared_keys[idx].clone()))
                } else {
                    Err(io::Error::new(io::ErrorKind::InvalidData,
                        format!("shared key ref {} out of range (have {})", idx, self.shared_keys.len())))
                }
            }

            // Long shared key reference (2 bytes)
            0x30..=0x33 if self.shared_keys_enabled => {
                let b2 = self.read_byte()? as usize;
                let idx = ((b - 0x30) as usize) * 256 + b2;
                if idx < self.shared_keys.len() {
                    Ok(Some(self.shared_keys[idx].clone()))
                } else {
                    Err(io::Error::new(io::ErrorKind::InvalidData,
                        format!("long shared key ref {} out of range", idx)))
                }
            }

            // Empty string key
            0x20 => Ok(Some(String::new())),

            // Short ASCII key (1-64 bytes)
            0x80..=0xBF => {
                let len = (b - 0x80 + 1) as usize;
                let s = self.read_safe_utf8(len)?;
                if self.shared_keys_enabled && s.len() <= 64 {
                    self.shared_keys.push(s.clone());
                }
                Ok(Some(s))
            }

            // Short Unicode key (2-57 bytes)
            0xC0..=0xF7 => {
                let len = (b - 0xC0 + 2) as usize;
                let s = self.read_safe_utf8(len)?;
                if self.shared_keys_enabled && s.len() <= 64 {
                    self.shared_keys.push(s.clone());
                }
                Ok(Some(s))
            }

            // Long ASCII key
            0x34 => {
                let s = self.read_long_string()?;
                Ok(Some(s))
            }

            // Long Unicode key
            0x35 => {
                let s = self.read_long_string()?;
                Ok(Some(s))
            }

            _ => Err(io::Error::new(io::ErrorKind::InvalidData,
                format!("unexpected key token: 0x{:02X} at pos {}", b, self.pos - 1))),
        }
    }

    fn read_value(&mut self) -> io::Result<SmileValue> {
        let b = self.read_byte()?;
        self.decode_value_token(b)
    }

    fn decode_value_token(&mut self, b: u8) -> io::Result<SmileValue> {
        match b {
            // Literals
            0x20 => Ok(SmileValue::String(String::new())), // empty string
            0x21 => Ok(SmileValue::Null),
            0x22 => Ok(SmileValue::Bool(false)),
            0x23 => Ok(SmileValue::Bool(true)),

            // 32-bit integer (signed VInt)
            0x24 => {
                let v = self.read_signed_vint()?;
                Ok(SmileValue::Int(v))
            }
            // 64-bit integer (signed VInt)
            0x25 => {
                let v = self.read_signed_vint()?;
                Ok(SmileValue::Int(v))
            }
            // BigInteger
            0x26 => {
                // Read as binary: VInt length + bytes
                let len = self.read_unsigned_vint()? as usize;
                let _bytes = self.read_bytes(len)?;
                Ok(SmileValue::Int(0)) // simplified
            }

            // 32-bit float (5 bytes: 7-bit encoded)
            0x28 => {
                // 4 data bytes encoded in 5 bytes (7 bits each, last has 4 bits)
                let raw = self.read_bytes(5)?;
                let bits = ((raw[0] as u32) << 28)
                    | ((raw[1] as u32) << 21)
                    | ((raw[2] as u32) << 14)
                    | ((raw[3] as u32) << 7)
                    | (raw[4] as u32);
                Ok(SmileValue::Float(f32::from_bits(bits) as f64))
            }
            // 64-bit double (10 bytes: 7-bit encoded)
            0x29 => {
                let raw = self.read_bytes(10)?;
                let bits = ((raw[0] as u64) << 63)
                    | ((raw[1] as u64) << 56)
                    | ((raw[2] as u64) << 49)
                    | ((raw[3] as u64) << 42)
                    | ((raw[4] as u64) << 35)
                    | ((raw[5] as u64) << 28)
                    | ((raw[6] as u64) << 21)
                    | ((raw[7] as u64) << 14)
                    | ((raw[8] as u64) << 7)
                    | (raw[9] as u64);
                Ok(SmileValue::Float(f64::from_bits(bits)))
            }

            // Small integers: C0-DF → zigzag decoded from 5-bit value
            0xC0..=0xDF => {
                let raw = (b & 0x1F) as u64;
                let v = zigzag_decode(raw);
                Ok(SmileValue::Int(v))
            }

            // Short ASCII value strings (1-64 bytes)
            0x40..=0x7F => {
                let len = (b - 0x40 + 1) as usize;
                let s = self.read_safe_utf8(len)?;
                if self.shared_values_enabled && s.len() <= 64 {
                    self.shared_values.push(s.clone());
                }
                Ok(SmileValue::String(s))
            }

            // Short Unicode value strings (2-65 bytes)
            0x80..=0xBF => {
                let len = (b - 0x80 + 2) as usize;
                let s = self.read_safe_utf8(len)?;
                if self.shared_values_enabled && s.len() <= 64 {
                    self.shared_values.push(s.clone());
                }
                Ok(SmileValue::String(s))
            }

            // Short shared value reference
            0x01..=0x1F => {
                let idx = (b - 1) as usize;
                if self.shared_values_enabled && idx < self.shared_values.len() {
                    Ok(SmileValue::String(self.shared_values[idx].clone()))
                } else {
                    Err(io::Error::new(io::ErrorKind::InvalidData,
                        format!("shared value ref {} out of range (have {})", idx, self.shared_values.len())))
                }
            }

            // Long shared value reference
            0xEC..=0xEF => {
                let b2 = self.read_byte()? as usize;
                let idx = ((b - 0xEC) as usize) * 256 + b2;
                if self.shared_values_enabled && idx < self.shared_values.len() {
                    Ok(SmileValue::String(self.shared_values[idx].clone()))
                } else {
                    Err(io::Error::new(io::ErrorKind::InvalidData,
                        format!("long shared value ref {} out of range", idx)))
                }
            }

            // Long ASCII string
            0xE0 => {
                let s = self.read_long_string()?;
                Ok(SmileValue::String(s))
            }

            // Long Unicode string
            0xE4 => {
                let s = self.read_long_string()?;
                Ok(SmileValue::String(s))
            }

            // 7-bit encoded binary
            0xE8 => {
                let len = self.read_unsigned_vint()? as usize;
                let encoded = self.read_bytes(len)?;
                let decoded = decode_7bit_binary(encoded);
                Ok(SmileValue::Binary(decoded))
            }

            // Raw binary (when ENCODE_BINARY_AS_7BIT=false)
            0xFD => {
                let len = self.read_unsigned_vint()? as usize;
                let bytes = self.read_bytes(len)?.to_vec();
                Ok(SmileValue::Binary(bytes))
            }

            // Array start
            0xF8 => {
                let mut arr = Vec::new();
                loop {
                    let next = self.peek_byte()?;
                    if next == 0xF9 {
                        self.pos += 1; // consume array end
                        break;
                    }
                    arr.push(self.read_value()?);
                }
                Ok(SmileValue::Array(arr))
            }

            // Object start
            0xFA => {
                let mut pairs = Vec::new();
                loop {
                    match self.read_key()? {
                        None => break, // object end (0xFB)
                        Some(key) => {
                            let value = self.read_value()?;
                            pairs.push((key, value));
                        }
                    }
                }
                Ok(SmileValue::Object(pairs))
            }

            _ => Err(io::Error::new(io::ErrorKind::InvalidData,
                format!("unexpected value token: 0x{:02X} at pos {}", b, self.pos - 1))),
        }
    }
}

/// Zigzag decode: maps unsigned to signed.
/// 0→0, 1→-1, 2→1, 3→-2, 4→2, ...
fn zigzag_decode(n: u64) -> i64 {
    ((n >> 1) as i64) ^ -((n & 1) as i64)
}

fn decode_7bit_binary(encoded: &[u8]) -> Vec<u8> {
    let mut decoded = Vec::new();
    let mut i = 0;
    while i < encoded.len() {
        let group_len = std::cmp::min(8, encoded.len() - i);
        if group_len < 2 {
            break;
        }
        let header = encoded[i];
        i += 1;
        for j in 0..(group_len - 1) {
            let b = encoded[i + j] | ((header >> j) & 1) << 7;
            decoded.push(b);
        }
        i += group_len - 1;
    }
    decoded
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_empty_object() {
        // SMILE header (shared values + raw binary) + empty object
        let data = [0x3A, 0x29, 0x0A, 0x05, 0xFA, 0xFB];
        let val = SmileParser::parse(&data).unwrap();
        match val {
            SmileValue::Object(pairs) => assert!(pairs.is_empty()),
            _ => panic!("expected object"),
        }
    }

    #[test]
    fn test_zigzag() {
        assert_eq!(zigzag_decode(0), 0);
        assert_eq!(zigzag_decode(1), -1);
        assert_eq!(zigzag_decode(2), 1);
        assert_eq!(zigzag_decode(3), -2);
        assert_eq!(zigzag_decode(4), 2);
        assert_eq!(zigzag_decode(6), 3);
    }
}
