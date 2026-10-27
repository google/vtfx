// Copyright 2026 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

//! Batch runner for `alacritty_terminal` used by `scripts/alacritty_diff.py`.
//!
//! Reads one test case per line from stdin:
//!   `CASE\t<id_hex>\t<cols>\t<rows>\t<scrollback>\t<styles_0_or_1>\t<ops>`
//! where `<ops>` is space-separated `W:<bytes_hex>` and `R:<cols>x<rows>` items.
//! Writes one result line per case to stdout:
//!   `OK\t<id_hex>\t<cursor_col>,<cursor_row>\t<row_hex,...>\t<style_hex,...>`
//! or, if `alacritty_terminal` panics on the input:
//!   `ERR\t<id_hex>\t<panic_msg_hex>`

use std::fmt::Write as _;
use std::io::{self, BufRead, BufWriter, Write};
use std::panic::{self, AssertUnwindSafe};

use alacritty_terminal::event::VoidListener;
use alacritty_terminal::grid::Dimensions;
use alacritty_terminal::index::{Column, Line};
use alacritty_terminal::term::cell::{Cell, Flags};
use alacritty_terminal::term::{Config, Term};
use alacritty_terminal::vte::ansi::{Color, NamedColor, Processor};

struct Size {
    columns: usize,
    screen_lines: usize,
}

impl Dimensions for Size {
    fn total_lines(&self) -> usize {
        self.screen_lines
    }

    fn screen_lines(&self) -> usize {
        self.screen_lines
    }

    fn columns(&self) -> usize {
        self.columns
    }
}

enum Op {
    Write(Vec<u8>),
    Resize(usize, usize),
}

fn decode_hex(hex: &str) -> Result<Vec<u8>, String> {
    if hex.len() % 2 != 0 {
        return Err(format!("odd hex length: {}", hex.len()));
    }
    let bytes = hex.as_bytes();
    let mut out = Vec::with_capacity(bytes.len() / 2);
    let mut i = 0;
    while i < bytes.len() {
        let hi = from_hex_digit(bytes[i])?;
        let lo = from_hex_digit(bytes[i + 1])?;
        out.push((hi << 4) | lo);
        i += 2;
    }
    Ok(out)
}

fn from_hex_digit(b: u8) -> Result<u8, String> {
    match b {
        b'0'..=b'9' => Ok(b - b'0'),
        b'a'..=b'f' => Ok(b - b'a' + 10),
        b'A'..=b'F' => Ok(b - b'A' + 10),
        _ => Err(format!("invalid hex byte: {b:#x}")),
    }
}

fn encode_hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

fn format_color(color: Color, default_named: NamedColor) -> String {
    match color {
        Color::Named(named) if named == default_named => "d".to_owned(),
        Color::Named(named) => {
            let code = named as u16;
            if code < 16 {
                format!("i{code}")
            } else {
                format!("n{code}")
            }
        }
        Color::Indexed(idx) => format!("i{idx}"),
        Color::Spec(rgb) => format!("r{:02x}{:02x}{:02x}", rgb.r, rgb.g, rgb.b),
    }
}

fn format_cell_style(cell: &Cell) -> String {
    let mut s = String::new();
    let _ = write!(
        s,
        "{}/{}/",
        format_color(cell.fg, NamedColor::Foreground),
        format_color(cell.bg, NamedColor::Background)
    );
    if cell.flags.contains(Flags::BOLD) {
        s.push('B');
    }
    if cell.flags.contains(Flags::DIM) {
        s.push('F');
    }
    if cell.flags.contains(Flags::ITALIC) {
        s.push('I');
    }
    if cell.flags.contains(Flags::UNDERLINE) {
        s.push_str("U1");
    } else if cell.flags.contains(Flags::DOUBLE_UNDERLINE) {
        s.push_str("U2");
    } else if cell.flags.contains(Flags::UNDERCURL) {
        s.push_str("U3");
    } else if cell.flags.contains(Flags::DOTTED_UNDERLINE) {
        s.push_str("U4");
    } else if cell.flags.contains(Flags::DASHED_UNDERLINE) {
        s.push_str("U5");
    }
    if cell.flags.contains(Flags::INVERSE) {
        s.push('V');
    }
    if cell.flags.contains(Flags::HIDDEN) {
        s.push('H');
    }
    if cell.flags.contains(Flags::STRIKEOUT) {
        s.push('S');
    }
    s.push('/');
    if cell.flags.intersects(Flags::ALL_UNDERLINES) {
        if let Some(ul) = cell.underline_color() {
            s.push_str(&format_color(ul, NamedColor::Foreground));
        }
    }
    s
}

fn run_case(
    columns: usize,
    rows: usize,
    scrollback: usize,
    include_styles: bool,
    ops: &[Op],
) -> (usize, i32, Vec<String>, Vec<String>) {
    let config = Config {
        scrolling_history: scrollback,
        kitty_keyboard: true,
        ..Config::default()
    };
    let initial = Size { columns, screen_lines: rows };
    let mut term = Term::new(config, &initial, VoidListener);
    let mut processor: Processor = Processor::new();

    for op in ops {
        match op {
            Op::Write(bytes) => processor.advance(&mut term, bytes),
            Op::Resize(cols, lines) => term.resize(Size { columns: *cols, screen_lines: *lines }),
        }
    }

    let grid = term.grid();
    let final_cols = grid.columns();
    let final_rows = grid.screen_lines();
    let cursor = grid.cursor.point;

    let mut line_hexes = Vec::with_capacity(final_rows);
    let mut style_hexes = if include_styles { Vec::with_capacity(final_rows) } else { Vec::new() };

    for row_idx in 0..final_rows {
        let row = &grid[Line(row_idx as i32)];
        let mut text = String::new();
        let mut row_styles = Vec::new();
        for col_idx in 0..final_cols {
            let cell = &row[Column(col_idx)];
            if cell.flags.contains(Flags::WIDE_CHAR_SPACER) {
                continue;
            }
            let ch = if cell.c == '\t' { ' ' } else { cell.c };
            text.push(ch);
            if let Some(zw) = cell.zerowidth() {
                for &z in zw {
                    text.push(z);
                }
            }
            if include_styles {
                row_styles.push(format_cell_style(cell));
            }
        }
        line_hexes.push(encode_hex(text.as_bytes()));
        if include_styles {
            style_hexes.push(encode_hex(row_styles.join(";").as_bytes()));
        }
    }

    (cursor.column.0, cursor.line.0, line_hexes, style_hexes)
}

fn parse_ops(raw: &str) -> Result<Vec<Op>, String> {
    let mut ops = Vec::new();
    for token in raw.split_whitespace() {
        if let Some(hex) = token.strip_prefix("W:") {
            ops.push(Op::Write(decode_hex(hex)?));
        } else if let Some(dim) = token.strip_prefix("R:") {
            let (c, r) = dim.split_once('x').ok_or_else(|| format!("bad resize: {token}"))?;
            let cols: usize = c.parse().map_err(|e| format!("bad resize cols: {e}"))?;
            let rows: usize = r.parse().map_err(|e| format!("bad resize rows: {e}"))?;
            ops.push(Op::Resize(cols, rows));
        } else {
            return Err(format!("unknown op: {token}"));
        }
    }
    Ok(ops)
}

fn panic_message(payload: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_owned()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "panic without string message".to_owned()
    }
}

fn main() -> io::Result<()> {
    // Silence default panic output on stderr; panics are caught and reported per test case.
    panic::set_hook(Box::new(|_| {}));

    let stdin = io::stdin();
    let stdout = io::stdout();
    let mut out = BufWriter::new(stdout.lock());

    for line_res in stdin.lock().lines() {
        let line = line_res?;
        if line.is_empty() {
            continue;
        }
        let parts: Vec<&str> = line.splitn(7, '\t').collect();
        if parts.len() < 6 || parts[0] != "CASE" {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "malformed CASE line"));
        }
        let id_hex = parts[1];
        let columns: usize = parts[2]
            .parse()
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("cols: {e}")))?;
        let rows: usize = parts[3]
            .parse()
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("rows: {e}")))?;
        let scrollback: usize = parts[4]
            .parse()
            .map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, format!("scrollback: {e}")))?;
        let include_styles = parts[5] == "1";
        let ops_str = if parts.len() == 7 { parts[6] } else { "" };
        let ops =
            parse_ops(ops_str).map_err(|e| io::Error::new(io::ErrorKind::InvalidInput, e))?;

        let outcome = panic::catch_unwind(AssertUnwindSafe(|| {
            run_case(columns, rows, scrollback, include_styles, &ops)
        }));

        match outcome {
            Ok((col, row, line_hexes, style_hexes)) => {
                writeln!(
                    out,
                    "OK\t{}\t{},{}\t{}\t{}",
                    id_hex,
                    col,
                    row,
                    line_hexes.join(","),
                    style_hexes.join(",")
                )?;
            }
            Err(payload) => {
                let msg = panic_message(payload);
                writeln!(out, "ERR\t{}\t{}", id_hex, encode_hex(msg.as_bytes()))?;
            }
        }
        out.flush()?;
    }
    Ok(())
}
