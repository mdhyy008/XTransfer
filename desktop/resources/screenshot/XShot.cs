using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace XShot
{
    internal static class Program
    {
        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        [DllImport("user32.dll")]
        private static extern bool SetProcessDpiAwarenessContext(IntPtr value);

        [DllImport("user32.dll")]
        private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter,
            int x, int y, int cx, int cy, uint flags);

        [DllImport("user32.dll")]
        private static extern IntPtr SetForegroundWindow(IntPtr hWnd);

        private static readonly IntPtr HWND_TOPMOST = new IntPtr(-1);
        private const uint SWP_SHOWWINDOW = 0x0040;

        [STAThread]
        private static int Main(string[] args)
        {
            string outPath = null;
            for (int i = 0; i < args.Length; i++)
            {
                if (args[i] == "--out" && i + 1 < args.Length)
                {
                    outPath = args[i + 1];
                }
            }
            if (string.IsNullOrEmpty(outPath))
            {
                return 2;
            }

            try
            {
                SetProcessDpiAwarenessContext(new IntPtr(-4));
            }
            catch
            {
                try { SetProcessDPIAware(); }
                catch { }
            }

            Screen screen = Screen.FromPoint(Cursor.Position);
            Rectangle bounds = screen.Bounds;
            Bitmap shot;
            try
            {
                shot = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppArgb);
                using (Graphics g = Graphics.FromImage(shot))
                {
                    g.CopyFromScreen(bounds.X, bounds.Y, 0, 0, bounds.Size, CopyPixelOperation.SourceCopy);
                }
            }
            catch
            {
                return 3;
            }

            OverlayForm form = new OverlayForm(shot, bounds, outPath);
            Application.Run(form);
            shot.Dispose();
            return form.ExitCode;
        }

        // 无边框置顶窗单独调一次 SetWindowPos，才能稳稳压住任务栏。
        internal static void BringToFront(Form form)
        {
            try
            {
                SetWindowPos(form.Handle, HWND_TOPMOST, form.Left, form.Top, form.Width, form.Height,
                    SWP_SHOWWINDOW);
                SetForegroundWindow(form.Handle);
            }
            catch { }
        }
    }

    internal sealed class OverlayForm : Form
    {
        public int ExitCode = 1;

        private readonly Bitmap _shot;
        private readonly string _outPath;
        private bool _selecting;
        private Point _start;
        private Point _current;

        public OverlayForm(Bitmap shot, Rectangle bounds, string outPath)
        {
            _shot = shot;
            _outPath = outPath;

            FormBorderStyle = FormBorderStyle.None;
            StartPosition = FormStartPosition.Manual;
            Bounds = bounds;
            TopMost = true;
            ShowInTaskbar = false;
            AutoScaleMode = AutoScaleMode.None;
            Cursor = Cursors.Cross;
            KeyPreview = true;
            DoubleBuffered = true;
            SetStyle(ControlStyles.AllPaintingInWmPaint | ControlStyles.UserPaint |
                     ControlStyles.OptimizedDoubleBuffer, true);
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            Program.BringToFront(this);
            Activate();
            Focus();
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;
            g.DrawImageUnscaled(_shot, 0, 0);

            Rectangle sel = Selection();
            using (SolidBrush dim = new SolidBrush(Color.FromArgb(115, 0, 0, 0)))
            {
                if (_selecting && sel.Width >= 1 && sel.Height >= 1)
                {
                    g.FillRectangle(dim, new Rectangle(0, 0, _shot.Width, sel.Top));
                    g.FillRectangle(dim, new Rectangle(0, sel.Bottom, _shot.Width, _shot.Height - sel.Bottom));
                    g.FillRectangle(dim, new Rectangle(0, sel.Top, sel.Left, sel.Height));
                    g.FillRectangle(dim, new Rectangle(sel.Right, sel.Top, _shot.Width - sel.Right, sel.Height));
                    using (Pen pen = new Pen(Color.FromArgb(235, 255, 255, 255), 1f))
                    {
                        g.DrawRectangle(pen, sel);
                    }
                }
                else
                {
                    g.FillRectangle(dim, new Rectangle(0, 0, _shot.Width, _shot.Height));
                }
            }
        }

        private Rectangle Selection()
        {
            int left = Math.Min(_start.X, _current.X);
            int top = Math.Min(_start.Y, _current.Y);
            int width = Math.Abs(_current.X - _start.X);
            int height = Math.Abs(_current.Y - _start.Y);
            return new Rectangle(left, top, width, height);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            base.OnMouseDown(e);
            if (e.Button == MouseButtons.Right)
            {
                Cancel();
                return;
            }
            if (e.Button != MouseButtons.Left) return;
            _selecting = true;
            _start = new Point(e.X, e.Y);
            _current = _start;
            Invalidate();
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            base.OnMouseMove(e);
            if (!_selecting) return;
            _current = new Point(e.X, e.Y);
            Invalidate();
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            base.OnMouseUp(e);
            if (!_selecting || e.Button != MouseButtons.Left) return;
            _current = new Point(e.X, e.Y);
            Rectangle sel = Selection();
            if (sel.Width < 3 || sel.Height < 3)
            {
                _selecting = false;
                Invalidate();
                return;
            }
            try
            {
                sel.Intersect(new Rectangle(0, 0, _shot.Width, _shot.Height));
                if (sel.Width < 1 || sel.Height < 1)
                {
                    _selecting = false;
                    Invalidate();
                    return;
                }
                using (Bitmap crop = _shot.Clone(sel, _shot.PixelFormat))
                {
                    using (Bitmap output = new Bitmap(crop.Width, crop.Height, PixelFormat.Format32bppArgb))
                    {
                        using (Graphics g = Graphics.FromImage(output))
                        {
                            g.DrawImageUnscaled(crop, 0, 0);
                        }
                        output.Save(_outPath, ImageFormat.Png);
                    }
                }
                ExitCode = 0;
            }
            catch
            {
                ExitCode = 3;
            }
            Close();
        }

        protected override bool ProcessDialogKey(Keys keyData)
        {
            if (keyData == Keys.Escape)
            {
                Cancel();
                return true;
            }
            return base.ProcessDialogKey(keyData);
        }

        private void Cancel()
        {
            ExitCode = 1;
            Close();
        }
    }
}
