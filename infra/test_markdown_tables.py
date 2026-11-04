import unittest

import markdown_tables as mt


class MarkdownTablesTest(unittest.TestCase):
    def test_numeric_columns_are_right_aligned_and_pipes_line_up(self):
        text = mt.table(['Group', 'Days', 'Median (min)'], [['all', 740, '-12.5'], ['sales', 665, 'n/a']])
        lines = text.splitlines()
        self.assertEqual(4, len(lines))
        self.assertEqual(1, len({len(line) for line in lines}), 'every row has the same width')
        self.assertEqual('| Group | Days | Median (min) |', lines[0])
        self.assertEqual('| ----- | ---: | -----------: |', lines[1])
        self.assertEqual('| all   |  740 |        -12.5 |', lines[2])
        self.assertEqual('| sales |  665 |          n/a |', lines[3])

    def test_a_text_column_stays_left_aligned(self):
        self.assertEqual('---', mt.align(['a', 'b', '3']))
        self.assertEqual('---:', mt.align(['1', '2', 'n/a', '']))

    def test_a_short_row_is_refused(self):
        with self.assertRaises(ValueError):
            mt.table(['a', 'b'], [['only one']])


if __name__ == '__main__':
    unittest.main()
