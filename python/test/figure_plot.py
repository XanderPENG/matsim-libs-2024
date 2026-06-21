"""
Author: Xander PENG
Date: 2026-01-30
File: figure_plot.py
Description: Provide some functions for figure plotting
"""
import matplotlib.pyplot as plt
import matplotlib.colors as mcolors
from matplotlib.colors import TwoSlopeNorm
import seaborn as sns
import numpy as np
from scipy import stats
from pathlib import Path


''' Set the matplotlib font globally as Arial '''
plt.rcParams["font.family"] = "Arial"

def generate_smooth_colors(input_colors, n_req_colors, colorspace='lab'):
    """
    Generate a list of smoothly interpolated hex colours from a set of
    anchor colours.

    The function builds a continuous gradient through the given
    ``input_colors`` (in the chosen colour-space) and samples
    ``n_req_colors`` evenly-spaced points along it, returning visually
    smooth and distinguishable hex strings.

    Args:
        input_colors: List of anchor colours in any matplotlib-accepted
                      format (hex strings, named colours, RGB tuples, etc.).
                      At least 2 colours are required for interpolation.
        n_req_colors: Number of output colours to generate.  Must be >= 1.
        colorspace: Colour-space used for interpolation.  One of

                    * ``'lab'`` (default) – perceptually uniform CIELAB;
                      produces the smoothest visual transitions.
                    * ``'rgb'`` – simple linear interpolation in sRGB;
                      faster but may show uneven brightness jumps.

    Returns:
        List[str]: A list of ``n_req_colors`` hex colour strings
                   (e.g. ``['#909E48', '#B07234', ...]``).

    Raises:
        ValueError: If fewer than 2 anchor colours are provided or
                    ``n_req_colors < 1``.

    Examples:
        >>> generate_smooth_colors(['#2166ac', '#f4a582', '#b2182b'], 5)
        ['#2166AC', '#6E8DB4', '#F4A582', '#D35E56', '#B2182B']

        >>> generate_smooth_colors(['steelblue', 'salmon', 'darkred'], 8)

        >>> # Use in plotting
        >>> colors = generate_smooth_colors(['#1b7837', '#f7f7f7', '#762a83'], 6)
        >>> plot_change_rate(pivot_df=df, colors=colors)
    """
    if len(input_colors) < 2:
        raise ValueError("At least 2 anchor colours are required for interpolation.")
    if n_req_colors < 1:
        raise ValueError("n_req_colors must be >= 1.")

    # Convert every anchor to RGB float tuple
    anchors_rgb = [mcolors.to_rgb(c) for c in input_colors]

    if n_req_colors == 1:
        return [mcolors.to_hex(anchors_rgb[0]).upper()]

    # ------------------------------------------------------------------
    # Build interpolation in the chosen colour-space
    # ------------------------------------------------------------------
    if colorspace == 'lab':
        # ---- CIELAB interpolation (perceptually uniform) ----
        def _rgb_to_xyz(rgb):
            """sRGB [0-1] → CIE XYZ (D65)."""
            r, g, b = rgb
            # Inverse sRGB companding
            def _linearize(v):
                return v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4
            rl, gl, bl = _linearize(r), _linearize(g), _linearize(b)
            # sRGB → XYZ (D65 matrix)
            x = 0.4124564 * rl + 0.3575761 * gl + 0.1804375 * bl
            y = 0.2126729 * rl + 0.7151522 * gl + 0.0721750 * bl
            z = 0.0193339 * rl + 0.1191920 * gl + 0.9503041 * bl
            return (x, y, z)

        def _xyz_to_lab(xyz):
            """CIE XYZ → CIELAB (D65 white point)."""
            xn, yn, zn = 0.95047, 1.0, 1.08883  # D65
            def _f(t):
                delta = 6 / 29
                return t ** (1 / 3) if t > delta ** 3 else t / (3 * delta ** 2) + 4 / 29
            fx = _f(xyz[0] / xn)
            fy = _f(xyz[1] / yn)
            fz = _f(xyz[2] / zn)
            L = 116 * fy - 16
            a = 500 * (fx - fy)
            b = 200 * (fy - fz)
            return (L, a, b)

        def _lab_to_xyz(lab):
            """CIELAB → CIE XYZ (D65)."""
            xn, yn, zn = 0.95047, 1.0, 1.08883
            L, a, b = lab
            fy = (L + 16) / 116
            fx = a / 500 + fy
            fz = fy - b / 200
            delta = 6 / 29
            def _finv(t):
                return t ** 3 if t > delta else 3 * delta ** 2 * (t - 4 / 29)
            return (_finv(fx) * xn, _finv(fy) * yn, _finv(fz) * zn)

        def _xyz_to_rgb(xyz):
            """CIE XYZ → sRGB [0-1] (D65)."""
            x, y, z = xyz
            rl =  3.2404542 * x - 1.5371385 * y - 0.4985314 * z
            gl = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
            bl =  0.0556434 * x - 0.2040259 * y + 1.0572252 * z
            def _compand(v):
                v = max(0.0, min(1.0, v))
                return 12.92 * v if v <= 0.0031308 else 1.055 * v ** (1 / 2.4) - 0.055
            return (_compand(rl), _compand(gl), _compand(bl))

        anchors_lab = [_xyz_to_lab(_rgb_to_xyz(c)) for c in anchors_rgb]
        anchors_np = np.array(anchors_lab)  # shape (n_anchors, 3)

        def _interp_lab(t):
            """Interpolate at position *t* ∈ [0, 1] along the anchor chain."""
            n_seg = len(anchors_np) - 1
            seg = min(int(t * n_seg), n_seg - 1)
            local_t = t * n_seg - seg
            lab = anchors_np[seg] * (1 - local_t) + anchors_np[seg + 1] * local_t
            return _xyz_to_rgb(_lab_to_xyz(tuple(lab)))

        positions = np.linspace(0, 1, n_req_colors)
        result = [mcolors.to_hex(_interp_lab(t)).upper() for t in positions]

    elif colorspace == 'rgb':
        # ---- Simple RGB interpolation ----
        anchors_np = np.array(anchors_rgb)  # (n_anchors, 3)

        def _interp_rgb(t):
            n_seg = len(anchors_np) - 1
            seg = min(int(t * n_seg), n_seg - 1)
            local_t = t * n_seg - seg
            rgb = anchors_np[seg] * (1 - local_t) + anchors_np[seg + 1] * local_t
            return tuple(np.clip(rgb, 0, 1))

        positions = np.linspace(0, 1, n_req_colors)
        result = [mcolors.to_hex(_interp_rgb(t)).upper() for t in positions]

    else:
        raise ValueError(
            f"Unknown colorspace '{colorspace}'. Use 'lab' or 'rgb'."
        )

    return result


def darken_color(color, factor=0.7):
    """
    Darken a color by a given factor.
    
    Args:
        color: Color in any matplotlib-accepted format
        factor: Factor to darken (0-1, smaller = darker)
    
    Returns:
        Darkened color as RGB tuple
    """
    rgb = mcolors.to_rgb(color)
    return tuple(c * factor for c in rgb)


def _fit_and_plot_curve(ax, data, x, bin_width, color, label, 
                        fitting_method='normal', n_components=2, kde_bw='scott'):
    """
    Internal helper to fit and plot distribution curves.
    
    Args:
        ax: Matplotlib axis
        data: 1D array of data values
        x: x-values for plotting the curve
        bin_width: Width of histogram bins (for scaling)
        color: Line color
        label: Label for legend
        fitting_method: 'normal', 'kde', or 'gmm'
        n_components: Number of Gaussian components for GMM (default: 2)
        kde_bw: Bandwidth for KDE ('scott', 'silverman', or float; smaller = more detail)
    """
    n = len(data)
    
    if fitting_method == 'normal':
        # Single Gaussian fit
        mu, std = stats.norm.fit(data)
        p = stats.norm.pdf(x, mu, std) * n * bin_width
        ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        
    elif fitting_method == 'kde':
        # Kernel Density Estimation - adapts to any distribution shape
        # kde_bw: smaller value = more detail/peaks; larger = smoother
        try:
            kde = stats.gaussian_kde(data, bw_method=kde_bw)
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"KDE fitting failed: {e}")
            
    elif fitting_method == 'gmm':
        # Gaussian Mixture Model - good for bimodal/multimodal distributions
        # Plots a single combined curve (sum of all components)
        try:
            from sklearn.mixture import GaussianMixture
            
            data_reshaped = np.array(data).reshape(-1, 1)
            gmm = GaussianMixture(n_components=n_components, random_state=42)
            gmm.fit(data_reshaped)
            
            # Compute combined GMM PDF (single curve)
            x_reshaped = x.reshape(-1, 1)
            log_prob = gmm.score_samples(x_reshaped)
            p = np.exp(log_prob) * n * bin_width
            
            # Plot as single combined curve
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
                        
        except ImportError:
            print("sklearn not available. Install with: pip install scikit-learn")
            # Fallback to KDE
            kde = stats.gaussian_kde(data, bw_method='scott')
            p = kde(x) * n * bin_width
            ax.plot(x, p, color=color, linewidth=2, linestyle=':', label=f"{label} Fitting")
        except Exception as e:
            print(f"GMM fitting failed: {e}")
    else:
        raise ValueError(f"Unknown fitting_method: {fitting_method}. Use 'normal', 'kde', or 'gmm'.")


def hist_plot(data_df,
              col1,
              col2=None,
              n_bins=50,
              figure_size=(10, 6),
              dpi=350,
              is_fitting=True,
              fitting_method='normal',
              n_components=2,
              kde_bw='scott',
              labels=None,
              figure_folder=None,
              filename=None,
              **kwargs
              ):
    """
    Plot histogram for the specified column(s) with optional distribution fitting.
    If col2 is provided, plot both columns together for comparison.

    Args:
        data_df: DataFrame containing the data
        col1: Name of the first column to plot
        col2: Name of the second column to plot (optional)
        n_bins: Number of bins for histogram (default: 50)
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        is_fitting: Whether to fit and plot distribution curves (default: True)
        fitting_method: Fitting method - 'normal', 'kde', or 'gmm' (default: 'normal')
            Can be a single string (applied to both columns) or a tuple (method1, method2)
            to use different methods for col1 and col2.
            - 'normal': Single Gaussian (for unimodal distributions)
            - 'kde': Kernel Density Estimation (non-parametric, adapts to any shape)
            - 'gmm': Gaussian Mixture Model (for bimodal/multimodal distributions)
        n_components: Number of Gaussian components for GMM fitting (default: 2)
            Can be a single int or tuple (n1, n2) for col1 and col2 separately.
        kde_bw: Bandwidth for KDE - 'scott', 'silverman', or float (default: 'scott')
            Smaller values (e.g., 0.1-0.3) = more detail/sharper peaks
            Larger values (e.g., 0.5-1.0) = smoother curve
            Can be a tuple (bw1, bw2) for col1 and col2 separately.
        labels: List of labels for legend [label1, label2] (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
    Keyword Args:
        colors: Tuple of colors for (col1, col2) (default: ('steelblue', 'darkgrey'))
        alphas: Tuple of alpha values for (col1, col2) (default: (0.6, 0.8))
        xlabel: X-axis label (default: '')
        ylabel: Y-axis label (default: 'Density')
        label_size: Font size for axis labels (default: 10)
        hide_labels: Whether to hide axis labels (default: True)
        hide_legends: Whether to hide legends (default: False)
        show: Whether to display the plot (default: True)
        
    Examples:
        # Same method for both columns
        hist_plot(df, 'VKT', 'iter0_VKT', fitting_method='normal')
        
        # Different methods: GMM for col1 (bimodal), normal for col2 (unimodal)
        hist_plot(df, 'collab_rate', 'iter0_vkt', fitting_method=('gmm', 'normal'))
        
        # KDE with custom bandwidth (smaller = more detail)
        hist_plot(df, 'metric', fitting_method='kde', kde_bw=0.2)
    """
    # Extract kwargs with defaults
    colors = kwargs.get('colors', ('#1664a2', '#ce5759'))
    alphas = kwargs.get('alphas', (0.8, 0.8))
    xlabel = kwargs.get('xlabel', '')
    ylabel = kwargs.get('ylabel', 'Density')
    label_size = kwargs.get('label_size', 10)
    hide_labels = kwargs.get('hide_labels', True)
    show = kwargs.get('show', True)

    # Parse fitting_method - support tuple for separate control
    if isinstance(fitting_method, tuple):
        fitting_method1 = fitting_method[0]
        fitting_method2 = fitting_method[1] if len(fitting_method) > 1 else fitting_method[0]
    else:
        fitting_method1 = fitting_method2 = fitting_method

    # Parse n_components - support tuple for separate control
    if isinstance(n_components, tuple):
        n_components1 = n_components[0]
        n_components2 = n_components[1] if len(n_components) > 1 else n_components[0]
    else:
        n_components1 = n_components2 = n_components

    # Parse kde_bw - support tuple for separate control
    if isinstance(kde_bw, tuple):
        kde_bw1 = kde_bw[0]
        kde_bw2 = kde_bw[1] if len(kde_bw) > 1 else kde_bw[0]
    else:
        kde_bw1 = kde_bw2 = kde_bw

    # Validate columns
    if col1 not in data_df.columns:
        raise ValueError(f"Column '{col1}' not found in data_df.")
    if col2 is not None and col2 not in data_df.columns:
        raise ValueError(f"Column '{col2}' not found in data_df.")

    # Get data series (drop NaN values)
    data1 = data_df[col1].dropna()
    data2 = data_df[col2].dropna() if col2 is not None else None

    # Set labels
    if labels is None:
        label1 = col1
        label2 = col2 if col2 is not None else None
    else:
        label1 = labels[0]
        label2 = labels[1] if len(labels) > 1 and col2 is not None else None

    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)

    # Calculate bin range across all data
    if col2 is not None:
        min_val = min(data1.min(), data2.min())
        max_val = max(data1.max(), data2.max())
    else:
        min_val = data1.min()
        max_val = data1.max()

    bins = np.linspace(min_val, max_val, n_bins)
    bin_width = bins[1] - bins[0]

    # Plot histograms (col2 first if exists, so col1 is on top)
    if col2 is not None:
        plt.hist(data2, bins=bins, color=colors[1], alpha=alphas[1], label=label2)
    plt.hist(data1, bins=bins, color=colors[0], alpha=alphas[0], label=label1)

    # Fit and plot distribution curves
    if is_fitting:
        xmin, xmax = plt.xlim()
        x = np.linspace(xmin, xmax, 1000)

        # Fit col1
        _fit_and_plot_curve(ax, data1, x, bin_width, 
                           darken_color(colors[0], 0.5), label1,
                           fitting_method1, n_components1, kde_bw1)

        # Fit col2 if provided
        if col2 is not None:
            _fit_and_plot_curve(ax, data2, x, bin_width,
                               darken_color(colors[1]), label2,
                               fitting_method2, n_components2, kde_bw2)

    # Hide the right and top spines
    ax.spines["right"].set_visible(False)
    ax.spines["top"].set_visible(False)

    # Set labels
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight="bold")
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight="bold")
    if hide_labels:
        ax.set_xlabel('')
        ax.set_ylabel('')

    # Set tick font sizes
    plt.xticks(fontsize=10)
    plt.yticks(fontsize=10)

    # Add legend
    if not kwargs.get('hide_legends'):
        ax.legend()

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()


def joint_scatter_plot(data_df,
                       x_col,
                       y_col,
                       n_bins=20,
                       figure_size=(8, 8),
                       dpi=350,
                       marginal_type='hist',
                       kde_bw='scott',
                       labels=None,
                       figure_folder=None,
                       filename=None,
                       # Second dataset (optional)
                       data_df2=None,
                       x_col2=None,
                       y_col2=None,
                       # Grouping columns for first dataset (optional)
                       color_group_col=None,
                       size_group_col=None,
                       **kwargs
                       ):
    """
    Create a joint scatter plot with marginal distributions (similar to seaborn jointplot).
    Main plot: scatter of x_col vs y_col
    Top marginal: histogram/KDE of x_col
    Right marginal: histogram/KDE of y_col

    Args:
        data_df: DataFrame containing the data
        x_col: Name of the column for x-axis (e.g., 'carrier_score')
        y_col: Name of the column for y-axis (e.g., 'receiver_score')
        n_bins: Number of bins for histogram (default: 20)
        figure_size: Figure size as tuple (width, height) (default: (8, 8))
        dpi: Figure resolution (default: 350)
        marginal_type: Type of marginal plot - 'hist', 'kde', or 'both' (default: 'hist')
        kde_bw: Bandwidth for KDE - 'scott', 'silverman', or float (default: 'scott')
        labels: Tuple of labels for (x_axis, y_axis) (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
        data_df2: Optional second DataFrame for overlay scatter (no marginal plots)
        x_col2: Column name for x-axis of second dataset
        y_col2: Column name for y-axis of second dataset
        
        color_group_col: Column name to color first dataset points by value (continuous colormap)
        size_group_col: Column name to size first dataset points by value

    Keyword Args:
        # First dataset scatter options
        scatter_color: Color for scatter points (default: '#1664a2', ignored if color_group_col is set)
        scatter_alpha: Alpha for scatter points (default: 0.6)
        scatter_size: Size for scatter points (default: 30, ignored if size_group_col is set)
        scatter_marker: Marker style for first dataset (default: 'o')
        scatter_edgecolor: Edge color for scatter points (default: 'white')
        scatter_linewidth: Edge line width for scatter points (default: 0.5)
        
        # Color grouping options (when color_group_col is set)
        cmap: Colormap name or object (default: auto-detect based on data type)
            - For categorical: auto-selects 'tab10'/'tab20' based on number of categories
            - For continuous: defaults to 'viridis'
        cmap_color_list: List of colors to create custom colormap (e.g., ['red', 'green', 'blue'])
            When provided, creates a ListedColormap from these colors.
        cmap_vmin: Min value for colormap normalization (default: auto, only for continuous)
        cmap_vmax: Max value for colormap normalization (default: auto, only for continuous)
        show_colorbar: Whether to show colorbar for continuous variables (default: True)
        show_color_legend: Whether to show legend for categorical variables (default: True)
        color_legend_loc: Location for categorical color legend (default: 'upper left')
        colorbar_label: Label for colorbar/legend title (default: color_group_col)
        colorbar_shrink: Shrink factor for colorbar (default: 0.6)
        categorical_threshold: Max unique values to treat as categorical (default: 12)
        
        # Size grouping options (when size_group_col is set)
        size_min: Minimum point size (default: 10)
        size_max: Maximum point size (default: 200)
        size_norm_vmin: Min value for size normalization (default: auto)
        size_norm_vmax: Max value for size normalization (default: auto)
        show_size_legend: Whether to show size legend (default: True)
        size_legend_title: Title for size legend (default: size_group_col)
        size_legend_loc: Location for size legend (default: 'upper right')
        size_legend_num: Number of entries in size legend (default: 4)
        
        # Second dataset scatter options
        scatter2_color: Color for second dataset points (default: '#ce5759')
        scatter2_alpha: Alpha for second dataset points (default: 0.6)
        scatter2_size: Size for second dataset points (default: 30)
        scatter2_marker: Marker style for second dataset (default: 'x')
        scatter2_edgecolor: Edge color for second dataset (default: 'white')
        scatter2_linewidth: Edge line width for second dataset (default: 0.5)
        scatter2_label: Label for second dataset in legend (default: None)
        
        # Histogram options
        hist_color: Color for histogram bars (default: scatter_color)
        hist_alpha: Alpha for histogram bars (default: 0.7)
        
        # KDE line options
        kde_color: Color for KDE line (default: darkened scatter_color)
        kde_linewidth: Line width for KDE (default: 2)
        kde_linestyle: Line style for KDE (default: '-')
        kde_alpha: Alpha for KDE line (default: 1.0)
        
        # Labels and display options
        xlabel: X-axis label (default: x_col)
        ylabel: Y-axis label (default: y_col)
        label_size: Font size for axis labels (default: 12)
        hide_labels: Whether to hide axis labels (default: False)
        marginal_label: Label for marginal axes (default: 'Density')
        marginal_label_size: Font size for marginal labels (default: 10)
        hide_marginal_labels: Whether to hide marginal labels (default: False)
        show: Whether to display the plot (default: True)
        add_regression: Whether to add regression line (default: False)
        show_corr: Whether to show correlation coefficient (default: True)

    Returns:
        fig: matplotlib Figure object
        axes: dict with 'main', 'top', 'right' axes

    Examples:
        # Basic usage
        joint_scatter_plot(df, 'final_carrier_score', 'total_receiver_scores',
                          marginal_type='both', show_corr=True)
        
        # With color grouping by a column
        joint_scatter_plot(df, 'carrier_score', 'receiver_score',
                          color_group_col='penalty', cmap='plasma')
        
        # With size grouping
        joint_scatter_plot(df, 'carrier_score', 'receiver_score',
                          size_group_col='cost_savings', size_min=20, size_max=150)
        
        # With second dataset overlay
        joint_scatter_plot(df1, 'x', 'y', data_df2=df2, x_col2='x', y_col2='y',
                          scatter2_marker='s', scatter2_color='red')
        
        # Combined: color + size grouping + second dataset
        joint_scatter_plot(df1, 'x', 'y',
                          color_group_col='penalty', size_group_col='savings',
                          data_df2=df2, x_col2='x2', y_col2='y2')
        
        # With custom color list for categorical variable
        joint_scatter_plot(df, 'x', 'y', color_group_col='category',
                          cmap_color_list=['#e41a1c', '#377eb8', '#4daf4a', '#984ea3'])
        
        # Categorical with auto-detected colormap
        joint_scatter_plot(df, 'x', 'y', color_group_col='depot_location')  # auto: tab10
    """
    # Extract kwargs with defaults - First dataset
    scatter_color = kwargs.get('scatter_color', '#1664a2')
    scatter_alpha = kwargs.get('scatter_alpha', 0.6)
    scatter_size = kwargs.get('scatter_size', 30)
    scatter_marker = kwargs.get('scatter_marker', 'o')
    scatter_edgecolor = kwargs.get('scatter_edgecolor', 'white')
    scatter_linewidth = kwargs.get('scatter_linewidth', 0.5)
    
    # Color grouping options
    cmap = kwargs.get('cmap', None)  # None = auto-detect
    cmap_color_list = kwargs.get('cmap_color_list', None)  # User-provided color list
    cmap_vmin = kwargs.get('cmap_vmin', None)
    cmap_vmax = kwargs.get('cmap_vmax', None)
    show_colorbar = kwargs.get('show_colorbar', True)
    show_color_legend = kwargs.get('show_color_legend', True)  # For categorical
    color_legend_loc = kwargs.get('color_legend_loc', 'upper left')
    colorbar_label = kwargs.get('colorbar_label', color_group_col)
    colorbar_shrink = kwargs.get('colorbar_shrink', 0.6)
    categorical_threshold = kwargs.get('categorical_threshold', 12)  # Max unique values to treat as categorical
    
    # Size grouping options
    size_min = kwargs.get('size_min', 10)
    size_max = kwargs.get('size_max', 200)
    size_norm_vmin = kwargs.get('size_norm_vmin', None)
    size_norm_vmax = kwargs.get('size_norm_vmax', None)
    
    # Second dataset options
    scatter2_color = kwargs.get('scatter2_color', '#ce5759')
    scatter2_alpha = kwargs.get('scatter2_alpha', 0.6)
    scatter2_size = kwargs.get('scatter2_size', 30)
    scatter2_marker = kwargs.get('scatter2_marker', 'x')
    scatter2_edgecolor = kwargs.get('scatter2_edgecolor', 'white')
    scatter2_linewidth = kwargs.get('scatter2_linewidth', 0.5)
    scatter2_label = kwargs.get('scatter2_label', None)
    
    # Histogram options
    hist_color = kwargs.get('hist_color', scatter_color)
    hist_alpha = kwargs.get('hist_alpha', 0.7)
    
    # KDE line options
    kde_color = kwargs.get('kde_color', darken_color(scatter_color, 0.6))
    kde_linewidth = kwargs.get('kde_linewidth', 2)
    kde_linestyle = kwargs.get('kde_linestyle', '-')
    kde_alpha = kwargs.get('kde_alpha', 1.0)
    
    # Labels and display
    xlabel = kwargs.get('xlabel', x_col)
    ylabel = kwargs.get('ylabel', y_col)
    label_size = kwargs.get('label_size', 12)
    hide_labels = kwargs.get('hide_labels', False)
    marginal_label = kwargs.get('marginal_label', 'Density')
    marginal_label_size = kwargs.get('marginal_label_size', 10)
    hide_marginal_labels = kwargs.get('hide_marginal_labels', False)
    show = kwargs.get('show', True)
    add_regression = kwargs.get('add_regression', False)
    show_corr = kwargs.get('show_corr', True)

    # Validate columns
    if x_col not in data_df.columns:
        raise ValueError(f"Column '{x_col}' not found in data_df.")
    if y_col not in data_df.columns:
        raise ValueError(f"Column '{y_col}' not found in data_df.")
    if color_group_col is not None and color_group_col not in data_df.columns:
        raise ValueError(f"Column '{color_group_col}' not found in data_df.")
    if size_group_col is not None and size_group_col not in data_df.columns:
        raise ValueError(f"Column '{size_group_col}' not found in data_df.")

    # Get data (drop NaN values for both columns together)
    valid_mask = data_df[x_col].notna() & data_df[y_col].notna()
    if color_group_col is not None:
        valid_mask = valid_mask & data_df[color_group_col].notna()
    if size_group_col is not None:
        valid_mask = valid_mask & data_df[size_group_col].notna()
    
    x_data = data_df.loc[valid_mask, x_col].values
    y_data = data_df.loc[valid_mask, y_col].values
    
    # Get color/size grouping data if specified
    color_data = data_df.loc[valid_mask, color_group_col].values if color_group_col else None
    size_data = data_df.loc[valid_mask, size_group_col].values if size_group_col else None

    # Set labels
    if labels is not None:
        xlabel = labels[0]
        ylabel = labels[1] if len(labels) > 1 else y_col

    # Create figure with GridSpec for layout
    fig = plt.figure(figsize=figure_size, dpi=dpi)
    
    # Define grid: main scatter is larger, marginals are smaller
    gs = fig.add_gridspec(4, 4, hspace=0.05, wspace=0.05)
    
    ax_main = fig.add_subplot(gs[1:4, 0:3])   # Main scatter plot (bottom-left 3x3)
    ax_top = fig.add_subplot(gs[0, 0:3], sharex=ax_main)    # Top marginal
    ax_right = fig.add_subplot(gs[1:4, 3], sharey=ax_main)  # Right marginal

    # ========== Main scatter plot - First dataset ==========
    
    # Determine if color_group_col is categorical or continuous
    is_categorical = False
    unique_color_values = None
    color_to_idx = None
    final_cmap = None
    
    if color_group_col is not None:
        # Check if categorical: string/object dtype, or few unique values
        unique_color_values = np.unique(color_data[~np.isnan(color_data)] if np.issubdtype(color_data.dtype, np.number) 
                                        else color_data)
        n_unique = len(unique_color_values)
        
        # Determine if categorical
        if not np.issubdtype(color_data.dtype, np.floating) or n_unique <= categorical_threshold:
            is_categorical = True
        
        if is_categorical:
            # Create mapping from unique values to indices
            color_to_idx = {val: i for i, val in enumerate(unique_color_values)}
            c_values = np.array([color_to_idx[v] for v in color_data])
            
            # Determine colormap for categorical data
            if cmap_color_list is not None:
                # User provided color list - create custom colormap
                from matplotlib.colors import ListedColormap
                # Ensure we have enough colors
                if len(cmap_color_list) < n_unique:
                    # Cycle colors if not enough
                    extended_colors = (cmap_color_list * ((n_unique // len(cmap_color_list)) + 1))[:n_unique]
                    final_cmap = ListedColormap(extended_colors)
                else:
                    final_cmap = ListedColormap(cmap_color_list[:n_unique])
            elif cmap is not None:
                # User provided cmap name
                if isinstance(cmap, str):
                    base_cmap = plt.cm.get_cmap(cmap)
                    # Sample n_unique colors from the colormap
                    if hasattr(base_cmap, 'N') and base_cmap.N >= n_unique:
                        # Discrete colormap (like tab10, Set1, etc.)
                        colors = [base_cmap(i) for i in range(n_unique)]
                    else:
                        # Continuous colormap - sample evenly
                        colors = [base_cmap(i / (n_unique - 1) if n_unique > 1 else 0.5) for i in range(n_unique)]
                    from matplotlib.colors import ListedColormap
                    final_cmap = ListedColormap(colors)
                else:
                    final_cmap = cmap
            else:
                # Auto-select categorical colormap based on number of categories
                if n_unique <= 10:
                    final_cmap = plt.cm.get_cmap('tab10')
                elif n_unique <= 20:
                    final_cmap = plt.cm.get_cmap('tab20')
                else:
                    # For many categories, use a continuous colormap sampled discretely
                    base_cmap = plt.cm.get_cmap('viridis')
                    colors = [base_cmap(i / (n_unique - 1)) for i in range(n_unique)]
                    from matplotlib.colors import ListedColormap
                    final_cmap = ListedColormap(colors)
            
            cmap_vmin = 0
            cmap_vmax = n_unique - 1
        else:
            # Continuous variable
            c_values = color_data
            if cmap_vmin is None:
                cmap_vmin = np.nanmin(c_values)
            if cmap_vmax is None:
                cmap_vmax = np.nanmax(c_values)
            
            # Use provided cmap or default to viridis
            if cmap is None:
                final_cmap = 'viridis'
            else:
                final_cmap = cmap
    else:
        c_values = scatter_color
    
    # Determine sizes for scatter
    if size_group_col is not None:
        # Normalize size_data to [size_min, size_max]
        s_values = size_data
        if size_norm_vmin is None:
            size_norm_vmin = np.nanmin(s_values)
        if size_norm_vmax is None:
            size_norm_vmax = np.nanmax(s_values)
        
        # Normalize to [0, 1] then scale to [size_min, size_max]
        if size_norm_vmax > size_norm_vmin:
            s_normalized = (s_values - size_norm_vmin) / (size_norm_vmax - size_norm_vmin)
        else:
            s_normalized = np.ones_like(s_values) * 0.5
        s_sizes = size_min + s_normalized * (size_max - size_min)
    else:
        s_sizes = scatter_size
    
    # Plot first dataset
    if color_group_col is not None:
        scatter1 = ax_main.scatter(x_data, y_data, c=c_values, cmap=final_cmap,
                                   vmin=cmap_vmin, vmax=cmap_vmax,
                                   alpha=scatter_alpha, s=s_sizes, 
                                   marker=scatter_marker,
                                   edgecolors=scatter_edgecolor, 
                                   linewidth=scatter_linewidth)
        
        if is_categorical:
            # Add color legend for categorical variable
            if show_color_legend:
                color_legend_handles = []
                color_legend_labels = []
                
                for i, val in enumerate(unique_color_values):
                    color = final_cmap(i / (len(unique_color_values) - 1) if len(unique_color_values) > 1 else 0)
                    handle = ax_main.scatter([], [], c=[color], s=scatter_size,
                                            marker=scatter_marker, alpha=scatter_alpha,
                                            edgecolors=scatter_edgecolor, linewidth=scatter_linewidth)
                    color_legend_handles.append(handle)
                    # Format label based on type
                    if isinstance(val, (int, np.integer)):
                        color_legend_labels.append(str(int(val)))
                    elif isinstance(val, (float, np.floating)):
                        color_legend_labels.append(f'{val:.3g}')
                    else:
                        color_legend_labels.append(str(val))
                
                color_legend = ax_main.legend(color_legend_handles, color_legend_labels,
                                              title=colorbar_label if colorbar_label else color_group_col,
                                              loc=color_legend_loc, framealpha=0.9, 
                                              fontsize=9, title_fontsize=10)
                ax_main.add_artist(color_legend)
        else:
            # Add colorbar for continuous variable
            if show_colorbar:
                cbar = fig.colorbar(scatter1, ax=ax_main, shrink=colorbar_shrink, pad=0.02)
                if colorbar_label:
                    cbar.set_label(colorbar_label, fontsize=10)
    else:
        scatter1 = ax_main.scatter(x_data, y_data, c=c_values, alpha=scatter_alpha, 
                                   s=s_sizes, marker=scatter_marker,
                                   edgecolors=scatter_edgecolor, 
                                   linewidth=scatter_linewidth)
    
    # ========== Size Legend (when size_group_col is set) ==========
    if size_group_col is not None:
        show_size_legend = kwargs.get('show_size_legend', True)
        size_legend_title = kwargs.get('size_legend_title', size_group_col)
        size_legend_loc = kwargs.get('size_legend_loc', 'upper right')
        size_legend_num = kwargs.get('size_legend_num', 4)  # Number of legend entries
        
        if show_size_legend:
            # Create legend handles for different sizes
            size_legend_values = np.linspace(size_norm_vmin, size_norm_vmax, size_legend_num)
            size_legend_sizes = size_min + ((size_legend_values - size_norm_vmin) / 
                                            (size_norm_vmax - size_norm_vmin + 1e-10)) * (size_max - size_min)
            
            # Determine color for legend markers
            if color_group_col is not None and final_cmap is not None:
                # Use middle color from colormap
                if hasattr(final_cmap, '__call__'):
                    legend_color = final_cmap(0.5)
                else:
                    legend_color = plt.cm.get_cmap(final_cmap)(0.5)
            else:
                legend_color = scatter_color
            
            # Create scatter handles for legend
            legend_handles = []
            for val, sz in zip(size_legend_values, size_legend_sizes):
                handle = ax_main.scatter([], [], s=sz, c=[legend_color], 
                                        marker=scatter_marker, alpha=scatter_alpha,
                                        edgecolors=scatter_edgecolor, linewidth=scatter_linewidth)
                legend_handles.append(handle)
            
            # Format legend labels
            legend_labels = [f'{val:.2g}' for val in size_legend_values]
            
            # Add size legend
            size_legend = ax_main.legend(legend_handles, legend_labels, 
                                         title=size_legend_title, loc=size_legend_loc,
                                         framealpha=0.9, fontsize=9, title_fontsize=10,
                                         labelspacing=1.2, handletextpad=1.5)
            ax_main.add_artist(size_legend)
    
    # ========== Second dataset (optional) ==========
    if data_df2 is not None and x_col2 is not None and y_col2 is not None:
        if x_col2 not in data_df2.columns:
            raise ValueError(f"Column '{x_col2}' not found in data_df2.")
        if y_col2 not in data_df2.columns:
            raise ValueError(f"Column '{y_col2}' not found in data_df2.")
        
        valid_mask2 = data_df2[x_col2].notna() & data_df2[y_col2].notna()
        x_data2 = data_df2.loc[valid_mask2, x_col2].values
        y_data2 = data_df2.loc[valid_mask2, y_col2].values
        
        ax_main.scatter(x_data2, y_data2, c=scatter2_color, alpha=scatter2_alpha,
                        s=scatter2_size, marker=scatter2_marker,
                        edgecolors=scatter2_edgecolor, linewidth=scatter2_linewidth,
                        label=scatter2_label)
        
        if scatter2_label:
            ax_main.legend(loc='best', fontsize=9)
    
    # Add regression line if requested
    if add_regression:
        slope, intercept, r_value, p_value, std_err = stats.linregress(x_data, y_data)
        x_fit = np.linspace(x_data.min(), x_data.max(), 100)
        y_fit = slope * x_fit + intercept
        ax_main.plot(x_fit, y_fit, color='red', linewidth=2, linestyle='--', 
                     label=f'y = {slope:.3f}x + {intercept:.3f}')
        ax_main.legend(loc='best', fontsize=9)
    
    # Show correlation coefficient
    if show_corr:
        corr = np.corrcoef(x_data, y_data)[0, 1]
        ax_main.annotate(f'r = {corr:.3f}', xy=(0.05, 0.95), xycoords='axes fraction',
                        fontsize=11, fontweight='bold', 
                        bbox=dict(boxstyle='round', facecolor='white', alpha=0.8))

    ax_main.grid(True, alpha=0.3)
    ax_main.spines['top'].set_visible(False)
    ax_main.spines['right'].set_visible(False)

    # ========== Top marginal (x distribution) - Only for first dataset ==========
    x_bins = np.linspace(x_data.min(), x_data.max(), n_bins)
    
    # Use scatter_color for hist if no color grouping, otherwise use a neutral color
    marginal_hist_color = hist_color
    marginal_kde_color = kde_color if color_group_col is None else darken_color(marginal_hist_color, 0.6)
    
    if marginal_type in ['hist', 'both']:
        ax_top.hist(x_data, bins=x_bins, color=marginal_hist_color, alpha=hist_alpha, 
                    edgecolor='white', linewidth=0.5)
    
    if marginal_type in ['kde', 'both']:
        try:
            kde_x = stats.gaussian_kde(x_data, bw_method=kde_bw)
            x_smooth = np.linspace(x_data.min(), x_data.max(), 200)
            if marginal_type == 'kde':
                # Scale KDE to fill the axis nicely
                ax_top.fill_between(x_smooth, kde_x(x_smooth), alpha=hist_alpha, color=marginal_hist_color)
                ax_top.plot(x_smooth, kde_x(x_smooth), color=marginal_kde_color, 
                           linewidth=kde_linewidth, linestyle=kde_linestyle, alpha=kde_alpha)
            else:
                # Scale KDE to match histogram
                bin_width = x_bins[1] - x_bins[0]
                ax_top.plot(x_smooth, kde_x(x_smooth) * len(x_data) * bin_width, 
                           color=marginal_kde_color, linewidth=kde_linewidth,
                           linestyle=kde_linestyle, alpha=kde_alpha)
        except Exception as e:
            print(f"KDE fitting for x failed: {e}")

    ax_top.tick_params(labelbottom=False)
    ax_top.spines['top'].set_visible(False)
    ax_top.spines['right'].set_visible(False)
    ax_top.spines['bottom'].set_visible(False)
    
    if not hide_marginal_labels:
        ax_top.set_ylabel(marginal_label, fontsize=marginal_label_size, fontweight='bold')
        # Align ylabel with main plot's ylabel by setting same x coordinate
        ax_top.yaxis.set_label_coords(-0.1, 0.5)

    # ========== Right marginal (y distribution) - Only for first dataset ==========
    y_bins = np.linspace(y_data.min(), y_data.max(), n_bins)
    
    if marginal_type in ['hist', 'both']:
        ax_right.hist(y_data, bins=y_bins, color=marginal_hist_color, alpha=hist_alpha,
                      edgecolor='white', linewidth=0.5, orientation='horizontal')
    
    if marginal_type in ['kde', 'both']:
        try:
            kde_y = stats.gaussian_kde(y_data, bw_method=kde_bw)
            y_smooth = np.linspace(y_data.min(), y_data.max(), 200)
            if marginal_type == 'kde':
                ax_right.fill_betweenx(y_smooth, kde_y(y_smooth), alpha=hist_alpha, color=marginal_hist_color)
                ax_right.plot(kde_y(y_smooth), y_smooth, color=marginal_kde_color, 
                             linewidth=kde_linewidth, linestyle=kde_linestyle, alpha=kde_alpha)
            else:
                bin_width = y_bins[1] - y_bins[0]
                ax_right.plot(kde_y(y_smooth) * len(y_data) * bin_width, y_smooth,
                             color=marginal_kde_color, linewidth=kde_linewidth,
                             linestyle=kde_linestyle, alpha=kde_alpha)
        except Exception as e:
            print(f"KDE fitting for y failed: {e}")

    ax_right.tick_params(labelleft=False)
    ax_right.spines['top'].set_visible(False)
    ax_right.spines['right'].set_visible(False)
    ax_right.spines['left'].set_visible(False)
    
    if not hide_marginal_labels:
        ax_right.set_xlabel(marginal_label, fontsize=marginal_label_size, fontweight='bold')

    # ========== Labels ==========
    if not hide_labels:
        ax_main.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax_main.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    return fig, {'main': ax_main, 'top': ax_top, 'right': ax_right}


def scatter_regression_plot(data_df,
                            x_col,
                            y_col,
                            # Second dataset (optional)
                            data_df2=None,
                            x_col2=None,
                            y_col2=None,
                            # Figure settings
                            figure_size=(8, 6),
                            dpi=350,
                            # Regression options
                            add_regression=False,
                            show_equation=True,
                            show_r2=True,
                            show_corr=False,
                            # Second dataset regression
                            add_regression2=False,
                            show_equation2=True,
                            show_r2_2=True,
                            # Labels
                            labels=None,
                            figure_folder=None,
                            filename=None,
                            **kwargs
                            ):
    """
    Create a scatter plot with optional regression line and statistics.
    
    Args:
        data_df: DataFrame containing the first dataset
        x_col: Column name for x-axis
        y_col: Column name for y-axis
        
        data_df2: Optional second DataFrame for overlay scatter
        x_col2: Column name for x-axis of second dataset (default: same as x_col)
        y_col2: Column name for y-axis of second dataset (default: same as y_col)
        
        figure_size: Figure size as tuple (width, height) (default: (8, 6))
        dpi: Figure resolution (default: 350)
        
        add_regression: Whether to add regression line for first dataset (default: False)
        show_equation: Whether to show regression equation (default: True)
        show_r2: Whether to show R² value (default: True)
        show_corr: Whether to show Pearson correlation coefficient (default: False)
        
        add_regression2: Whether to add regression line for second dataset (default: False)
        show_equation2: Whether to show regression equation for second dataset (default: True)
        show_r2_2: Whether to show R² for second dataset (default: True)
        
        labels: Tuple of labels for (x_axis, y_axis) (default: column names)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)

    Keyword Args:
        # First dataset scatter options
        scatter_color: Color for scatter points (default: '#1664a2')
        scatter_alpha: Alpha for scatter points (default: 0.6)
        scatter_size: Size for scatter points (default: 40)
        scatter_marker: Marker style (default: 'o')
        scatter_edgecolor: Edge color for scatter points (default: 'white')
        scatter_linewidth: Edge line width (default: 0.5)
        scatter_label: Label for first dataset in legend (default: None)
        
        # First dataset regression line options
        reg_color: Color for regression line (default: darkened scatter_color)
        reg_linewidth: Line width for regression line (default: 2)
        reg_linestyle: Line style for regression line (default: '--')
        reg_alpha: Alpha for regression line (default: 1.0)
        
        # Second dataset scatter options
        scatter2_color: Color for second dataset points (default: '#ce5759')
        scatter2_alpha: Alpha for second dataset points (default: 0.6)
        scatter2_size: Size for second dataset points (default: 40)
        scatter2_marker: Marker style for second dataset (default: 's')
        scatter2_edgecolor: Edge color (default: 'white')
        scatter2_linewidth: Edge line width (default: 0.5)
        scatter2_label: Label for second dataset in legend (default: None)
        
        # Second dataset regression line options
        reg2_color: Color for second regression line (default: darkened scatter2_color)
        reg2_linewidth: Line width (default: 2)
        reg2_linestyle: Line style (default: ':')
        reg2_alpha: Alpha (default: 1.0)
        
        # Annotation options
        annotation_fontsize: Font size for equation/R² annotation (default: 10)
        annotation_loc: Location for annotation - 'auto', 'upper left', 'upper right', 
                        'lower left', 'lower right' (default: 'auto')
        annotation2_loc: Location for second dataset annotation (default: 'auto')
        
        # Labels and display options
        xlabel: X-axis label (default: x_col)
        ylabel: Y-axis label (default: y_col)
        title: Plot title (default: '')
        label_size: Font size for axis labels (default: 12)
        title_size: Font size for title (default: 14)
        hide_labels: Whether to hide axis labels (default: False)
        show_legend: Whether to show legend (default: True when labels provided)
        legend_loc: Location for legend (default: 'best')
        show_grid: Whether to show grid (default: True)
        grid_alpha: Alpha for grid (default: 0.3)
        show: Whether to display the plot (default: True)
        
        # Axis tick step options
        y_tick_step: Step size for y-axis major ticks (default: None, auto)
            e.g., 100 means one tick every 100 units.
        x_tick_step: Step size for x-axis major ticks (default: None, auto)
            e.g., 0.005 means one tick every 0.005 units.

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object
        stats_dict: Dictionary containing regression statistics

    Examples:
        # Basic scatter
        scatter_regression_plot(df, 'x', 'y')
        
        # With regression line
        scatter_regression_plot(df, 'x', 'y', add_regression=True)
        
        # Two datasets comparison
        scatter_regression_plot(df1, 'x', 'y', 
                               data_df2=df2, x_col2='x', y_col2='y',
                               scatter_label='Dataset 1', scatter2_label='Dataset 2',
                               add_regression=True, add_regression2=True)
        
        # Custom styling
        scatter_regression_plot(df, 'x', 'y',
                               scatter_color='green', scatter_marker='^', scatter_size=60,
                               add_regression=True, reg_linestyle='-', reg_color='darkgreen')
    """
    # Extract kwargs with defaults - First dataset scatter
    scatter_color = kwargs.get('scatter_color', '#1664a2')
    scatter_alpha = kwargs.get('scatter_alpha', 0.6)
    scatter_size = kwargs.get('scatter_size', 40)
    scatter_marker = kwargs.get('scatter_marker', 'o')
    scatter_edgecolor = kwargs.get('scatter_edgecolor', 'white')
    scatter_linewidth = kwargs.get('scatter_linewidth', 0.5)
    scatter_label = kwargs.get('scatter_label', None)
    
    # First dataset regression line
    reg_color = kwargs.get('reg_color', darken_color(scatter_color, 0.6))
    reg_linewidth = kwargs.get('reg_linewidth', 2)
    reg_linestyle = kwargs.get('reg_linestyle', '--')
    reg_alpha = kwargs.get('reg_alpha', 1.0)
    
    # Second dataset scatter
    scatter2_color = kwargs.get('scatter2_color', '#ce5759')
    scatter2_alpha = kwargs.get('scatter2_alpha', 0.6)
    scatter2_size = kwargs.get('scatter2_size', 40)
    scatter2_marker = kwargs.get('scatter2_marker', 's')
    scatter2_edgecolor = kwargs.get('scatter2_edgecolor', 'white')
    scatter2_linewidth = kwargs.get('scatter2_linewidth', 0.5)
    scatter2_label = kwargs.get('scatter2_label', None)
    
    # Second dataset regression line
    reg2_color = kwargs.get('reg2_color', darken_color(scatter2_color, 0.6))
    reg2_linewidth = kwargs.get('reg2_linewidth', 2)
    reg2_linestyle = kwargs.get('reg2_linestyle', ':')
    reg2_alpha = kwargs.get('reg2_alpha', 1.0)
    
    # Annotation options
    annotation_fontsize = kwargs.get('annotation_fontsize', 10)
    annotation_loc = kwargs.get('annotation_loc', 'auto')
    annotation2_loc = kwargs.get('annotation2_loc', 'auto')
    
    # Labels and display
    xlabel = kwargs.get('xlabel', x_col)
    ylabel = kwargs.get('ylabel', y_col)
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    hide_labels = kwargs.get('hide_labels', False)
    show_legend = kwargs.get('show_legend', None)  # Auto-detect
    legend_loc = kwargs.get('legend_loc', 'best')
    show_grid = kwargs.get('show_grid', True)
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    show = kwargs.get('show', True)
    y_tick_step = kwargs.get('y_tick_step', None)
    x_tick_step = kwargs.get('x_tick_step', None)

    # Validate columns
    if x_col not in data_df.columns:
        raise ValueError(f"Column '{x_col}' not found in data_df.")
    if y_col not in data_df.columns:
        raise ValueError(f"Column '{y_col}' not found in data_df.")

    # Get first dataset (drop NaN values)
    valid_mask = data_df[x_col].notna() & data_df[y_col].notna()
    x_data = data_df.loc[valid_mask, x_col].values
    y_data = data_df.loc[valid_mask, y_col].values

    # Set labels from parameter
    if labels is not None:
        xlabel = labels[0]
        ylabel = labels[1] if len(labels) > 1 else y_col

    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    
    # Stats dictionary to return
    stats_dict = {}

    # ========== Plot first dataset ==========
    ax.scatter(x_data, y_data, c=scatter_color, alpha=scatter_alpha,
               s=scatter_size, marker=scatter_marker,
               edgecolors=scatter_edgecolor, linewidth=scatter_linewidth,
               label=scatter_label)
    
    # Regression for first dataset
    if add_regression:
        slope, intercept, r_value, p_value, std_err = stats.linregress(x_data, y_data)
        r2 = r_value ** 2
        
        # Store stats
        stats_dict['dataset1'] = {
            'slope': slope,
            'intercept': intercept,
            'r': r_value,
            'r2': r2,
            'p_value': p_value,
            'std_err': std_err
        }
        
        # Plot regression line
        x_fit = np.linspace(x_data.min(), x_data.max(), 100)
        y_fit = slope * x_fit + intercept
        ax.plot(x_fit, y_fit, color=reg_color, linewidth=reg_linewidth,
                linestyle=reg_linestyle, alpha=reg_alpha)
        
        # Build annotation text
        annotation_parts = []
        if show_equation:
            sign = '+' if intercept >= 0 else '-'
            annotation_parts.append(f'y = {slope:.3f}x {sign} {abs(intercept):.3f}')
        if show_r2:
            annotation_parts.append(f'R² = {r2:.3f}')
        if show_corr:
            annotation_parts.append(f'coef. = {r_value:.2f}')
        
        if annotation_parts:
            annotation_text = '\n'.join(annotation_parts)
            
            # Determine annotation location
            if annotation_loc == 'auto':
                # Place based on slope direction
                if slope >= 0:
                    loc_xy = (0.05, 0.95)
                    va = 'top'
                else:
                    loc_xy = (0.05, 0.05)
                    va = 'bottom'
            else:
                loc_map = {
                    'upper left': ((0.05, 0.95), 'top'),
                    'upper right': ((0.95, 0.95), 'top'),
                    'lower left': ((0.05, 0.05), 'bottom'),
                    'lower right': ((0.95, 0.05), 'bottom'),
                }
                loc_xy, va = loc_map.get(annotation_loc, ((0.05, 0.95), 'top'))
            
            ha = 'left' if loc_xy[0] < 0.5 else 'right'
            
            ax.annotate(annotation_text, xy=loc_xy, xycoords='axes fraction',
                       fontsize=annotation_fontsize, fontweight='bold',
                       ha=ha, va=va,
                       bbox=dict(boxstyle='round', facecolor='white', alpha=0.8,
                                edgecolor=reg_color, linewidth=1))

    # ========== Plot second dataset (optional) ==========
    if data_df2 is not None:
        # Use same column names if not specified
        if x_col2 is None:
            x_col2 = x_col
        if y_col2 is None:
            y_col2 = y_col
            
        if x_col2 not in data_df2.columns:
            raise ValueError(f"Column '{x_col2}' not found in data_df2.")
        if y_col2 not in data_df2.columns:
            raise ValueError(f"Column '{y_col2}' not found in data_df2.")
        
        valid_mask2 = data_df2[x_col2].notna() & data_df2[y_col2].notna()
        x_data2 = data_df2.loc[valid_mask2, x_col2].values
        y_data2 = data_df2.loc[valid_mask2, y_col2].values
        
        ax.scatter(x_data2, y_data2, c=scatter2_color, alpha=scatter2_alpha,
                   s=scatter2_size, marker=scatter2_marker,
                   edgecolors=scatter2_edgecolor, linewidth=scatter2_linewidth,
                   label=scatter2_label)
        
        # Regression for second dataset
        if add_regression2:
            slope2, intercept2, r_value2, p_value2, std_err2 = stats.linregress(x_data2, y_data2)
            r2_2 = r_value2 ** 2
            
            # Store stats
            stats_dict['dataset2'] = {
                'slope': slope2,
                'intercept': intercept2,
                'r': r_value2,
                'r2': r2_2,
                'p_value': p_value2,
                'std_err': std_err2
            }
            
            # Plot regression line
            x_fit2 = np.linspace(x_data2.min(), x_data2.max(), 100)
            y_fit2 = slope2 * x_fit2 + intercept2
            ax.plot(x_fit2, y_fit2, color=reg2_color, linewidth=reg2_linewidth,
                    linestyle=reg2_linestyle, alpha=reg2_alpha)
            
            # Build annotation text for second dataset
            annotation_parts2 = []
            if show_equation2:
                sign2 = '+' if intercept2 >= 0 else '-'
                annotation_parts2.append(f'y = {slope2:.3f}x {sign2} {abs(intercept2):.3f}')
            if show_r2_2:
                annotation_parts2.append(f'R² = {r2_2:.3f}')
            
            if annotation_parts2:
                annotation_text2 = '\n'.join(annotation_parts2)
                
                # Determine annotation location for second dataset
                if annotation2_loc == 'auto':
                    # Place on opposite side from first annotation
                    if add_regression and slope >= 0:
                        loc_xy2 = (0.95, 0.05)
                        va2 = 'bottom'
                    else:
                        loc_xy2 = (0.95, 0.95)
                        va2 = 'top'
                else:
                    loc_map = {
                        'upper left': ((0.05, 0.95), 'top'),
                        'upper right': ((0.95, 0.95), 'top'),
                        'lower left': ((0.05, 0.05), 'bottom'),
                        'lower right': ((0.95, 0.05), 'bottom'),
                    }
                    loc_xy2, va2 = loc_map.get(annotation2_loc, ((0.95, 0.05), 'bottom'))
                
                ha2 = 'left' if loc_xy2[0] < 0.5 else 'right'
                
                ax.annotate(annotation_text2, xy=loc_xy2, xycoords='axes fraction',
                           fontsize=annotation_fontsize, fontweight='bold',
                           ha=ha2, va=va2,
                           bbox=dict(boxstyle='round', facecolor='white', alpha=0.8,
                                    edgecolor=reg2_color, linewidth=1))

    # ========== Styling ==========
    if show_grid:
        ax.grid(True, alpha=grid_alpha)
    
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    
    # Labels
    if not hide_labels:
        ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')
    
    # Legend
    if show_legend is None:
        # Auto-detect: show legend if any label is provided
        show_legend = scatter_label is not None or scatter2_label is not None
    
    if show_legend:
        ax.legend(loc=legend_loc, framealpha=0.9)

    # Apply custom tick steps
    if y_tick_step is not None:
        from matplotlib.ticker import MultipleLocator
        ax.yaxis.set_major_locator(MultipleLocator(y_tick_step))
    if x_tick_step is not None:
        from matplotlib.ticker import MultipleLocator
        ax.xaxis.set_major_locator(MultipleLocator(x_tick_step))

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    return fig, ax, stats_dict

def stacked_proportion_plot(data_df,
                            bin_col,
                            value_col,
                            n_bins=4,
                            bin_method='quantile',
                            custom_bins=None,
                            figure_size=(10, 6),
                            dpi=350,
                            figure_folder=None,
                            filename=None,
                            **kwargs
                            ):
    """
    Create a 100% stacked bar chart showing the proportion of value categories 
    across bins of another variable.
    
    This is useful for analyzing how the distribution of a discrete variable (e.g., 
    diff_fleet_size = 1, 2, 3) changes across ranges of a continuous variable 
    (e.g., collaboration_rate = 0.4-0.6, 0.6-0.8, 0.8-1.0).
    
    Args:
        data_df: DataFrame containing the data
        bin_col: Column name to bin/group (e.g., 'collaboration_rate')
        value_col: Column name for value categories to show proportions (e.g., 'diff_fleet_size')
        n_bins: Number of bins to create (default: 4)
        bin_method: Method for binning - 'quantile', 'equal', 'custom', or 'unique' (default: 'quantile')
            - 'quantile': Equal frequency bins (each bin has ~same number of samples)
            - 'equal': Equal width bins (evenly spaced)
            - 'custom': Use custom_bins parameter
            - 'unique': Use each unique value in bin_col directly as a category (no aggregation)
        custom_bins: List of bin edges for 'custom' method (e.g., [0, 0.4, 0.6, 0.8, 1.0])
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)

    Keyword Args:
        # Color options
        colors: List of colors for each value category (default: auto from colormap)
        cmap: Colormap name for auto-generating colors (default: 'tab10')
        
        # Bar options
        bar_width: Width of bars (default: 0.7)
        bar_edgecolor: Edge color for bars (default: 'white')
        bar_linewidth: Edge line width for bars (default: 1)
        
        # Value display options
        show_percentages: Whether to show percentage labels on bars (default: True)
        percentage_threshold: Min percentage to show label (default: 5)
        percentage_fontsize: Font size for percentage labels (default: 10)
        percentage_color: Color for percentage labels (default: 'white')
        
        # Count display options
        show_counts: Whether to show sample count above each bar (default: True)
        count_fontsize: Font size for count labels (default: 10)
        count_format: Format string for count (default: 'n={n}')
        
        # Labels and display options
        xlabel: X-axis label (default: bin_col)
        ylabel: Y-axis label (default: 'Proportion (%)')
        title: Plot title (default: '')
        label_size: Font size for axis labels (default: 12)
        title_size: Font size for title (default: 14)
        
        # Legend options
        show_legend: Whether to show legend (default: True)
        legend_title: Title for legend (default: value_col)
        legend_loc: Location for legend (default: 'upper right')
        legend_bbox: Bbox_to_anchor for legend (default: None)
        
        # Bin label options
        bin_label_format: Format for bin labels - 'range', 'midpoint', or custom callable
            (default: 'range')
        bin_decimal: Decimal places for bin labels (default: 2)
        
        # X-tick customization options
        xtick_labels: List of custom x-tick labels (must match number of bins).
            Overrides auto-generated bin labels. (default: None)
        xtick_rotation: Rotation angle for x-tick labels in degrees (default: 45)
        xtick_fontsize: Font size for x-tick labels (default: None, uses matplotlib default)
        xtick_ha: Horizontal alignment for x-tick labels, e.g. 'right', 'center', 'left'
            (default: 'right')
        xtick_va: Vertical alignment for x-tick labels (default: None, uses matplotlib default)
        xtick_kwargs: Additional keyword arguments passed to ax.set_xticklabels() (default: {})
        ytick_fontsize: Font size for y-tick labels (default: None, uses matplotlib default)
        
        show: Whether to display the plot (default: True)

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object
        result_df: DataFrame with proportion data

    Examples:
        # Basic usage - analyze fleet diff distribution across collaboration rate bins
        stacked_proportion_plot(df, 'collaboration_rate', 'diff_fleet_size', n_bins=4)
        
        # Equal width bins with custom colors
        stacked_proportion_plot(df, 'collab_rate', 'fleet_diff',
                               bin_method='equal', n_bins=5,
                               colors=['#e41a1c', '#377eb8', '#4daf4a'])
        
        # Custom bin edges
        stacked_proportion_plot(df, 'collaboration_rate', 'diff_fleet_size',
                               bin_method='custom',
                               custom_bins=[0, 0.4, 0.6, 0.8, 1.0])
        
        # Hide percentages, show only counts
        stacked_proportion_plot(df, 'rate', 'category',
                               show_percentages=False, show_counts=True)
    """
    import pandas as pd
    
    # Extract kwargs with defaults
    colors = kwargs.get('colors', None)
    cmap_name = kwargs.get('cmap', 'tab10')
    
    bar_width = kwargs.get('bar_width', 0.7)
    bar_edgecolor = kwargs.get('bar_edgecolor', 'white')
    bar_linewidth = kwargs.get('bar_linewidth', 1)
    
    show_percentages = kwargs.get('show_percentages', True)
    percentage_threshold = kwargs.get('percentage_threshold', 5)
    percentage_fontsize = kwargs.get('percentage_fontsize', 10)
    percentage_color = kwargs.get('percentage_color', 'white')
    
    show_counts = kwargs.get('show_counts', True)
    count_fontsize = kwargs.get('count_fontsize', 10)
    count_format = kwargs.get('count_format', 'n={n}')
    
    xlabel = kwargs.get('xlabel', bin_col)
    ylabel = kwargs.get('ylabel', 'Proportion (%)')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    
    show_legend = kwargs.get('show_legend', True)
    legend_title = kwargs.get('legend_title', value_col)
    legend_loc = kwargs.get('legend_loc', 'upper right')
    legend_bbox = kwargs.get('legend_bbox', None)
    legend_ncol = kwargs.get('legend_ncol', 1)  # Number of columns in legend
    
    bin_label_format = kwargs.get('bin_label_format', 'range')
    bin_decimal = kwargs.get('bin_decimal', 2)
    
    xtick_labels_custom = kwargs.get('xtick_labels', None)
    xtick_rotation = kwargs.get('xtick_rotation', 45)
    xtick_fontsize = kwargs.get('xtick_fontsize', None)
    xtick_ha = kwargs.get('xtick_ha', 'right')
    xtick_va = kwargs.get('xtick_va', None)
    xtick_extra = kwargs.get('xtick_kwargs', {})
    ytick_fontsize = kwargs.get('ytick_fontsize', None)
    
    show = kwargs.get('show', True)

    # Validate columns
    if bin_col not in data_df.columns:
        raise ValueError(f"Column '{bin_col}' not found in data_df.")
    if value_col not in data_df.columns:
        raise ValueError(f"Column '{value_col}' not found in data_df.")

    # Drop NaN values
    df = data_df[[bin_col, value_col]].dropna().copy()
    
    # Create bins
    # Note: include_lowest=True makes the first interval left-inclusive [a, b] instead of (a, b]
    # This ensures boundary values like exactly 0.4 are included when custom_bins=[0.4, 0.6, ...]
    _use_unique = False
    if bin_method == 'unique':
        _use_unique = True
        sorted_unique_bins = sorted(df[bin_col].unique())
        df['bin'] = pd.Categorical(df[bin_col], categories=sorted_unique_bins, ordered=True)
    elif bin_method == 'quantile':
        df['bin'], bin_edges = pd.qcut(df[bin_col], q=n_bins, retbins=True, duplicates='drop')
    elif bin_method == 'equal':
        df['bin'], bin_edges = pd.cut(df[bin_col], bins=n_bins, retbins=True, include_lowest=True)
    elif bin_method == 'custom':
        if custom_bins is None:
            raise ValueError("custom_bins must be provided when bin_method='custom'")
        df['bin'], bin_edges = pd.cut(df[bin_col], bins=custom_bins, retbins=True, include_lowest=True)
    else:
        raise ValueError(f"Unknown bin_method: {bin_method}. Use 'quantile', 'equal', 'custom', or 'unique'.")
    
    # Get unique values in value_col (sorted)
    unique_values = sorted(df[value_col].dropna().unique())
    n_values = len(unique_values)
    
    # Create bin labels
    if _use_unique:
        bin_categories = sorted_unique_bins
        n_actual_bins = len(bin_categories)
        bin_labels = [str(cat) for cat in bin_categories]
    else:
        bin_categories = df['bin'].cat.categories
        n_actual_bins = len(bin_categories)
        
        if callable(bin_label_format):
            bin_labels = [bin_label_format(interval) for interval in bin_categories]
        elif bin_label_format == 'range':
            bin_labels = [f'{interval.left:.{bin_decimal}f}-{interval.right:.{bin_decimal}f}' 
                         for interval in bin_categories]
        elif bin_label_format == 'midpoint':
            bin_labels = [f'{interval.mid:.{bin_decimal}f}' for interval in bin_categories]
        else:
            bin_labels = [str(interval) for interval in bin_categories]
    
    # Calculate proportions
    crosstab = pd.crosstab(df['bin'], df[value_col], normalize='index') * 100
    counts = df.groupby('bin').size()
    
    # Ensure all values are present in crosstab
    for val in unique_values:
        if val not in crosstab.columns:
            crosstab[val] = 0
    crosstab = crosstab[unique_values]  # Reorder columns
    
    # Generate colors if not provided
    if colors is None:
        cmap = plt.cm.get_cmap(cmap_name)
        colors = [cmap(i) for i in range(n_values)]
    elif len(colors) < n_values:
        # Extend colors if not enough
        colors = list(colors) * ((n_values // len(colors)) + 1)
        colors = colors[:n_values]
    
    # Create figure
    fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    
    # Plot stacked bars
    x_positions = np.arange(n_actual_bins)
    bottom = np.zeros(n_actual_bins)
    
    bars_dict = {}
    for i, val in enumerate(unique_values):
        heights = crosstab[val].values
        bars = ax.bar(x_positions, heights, bar_width, bottom=bottom,
                     label=str(val), color=colors[i],
                     edgecolor=bar_edgecolor, linewidth=bar_linewidth)
        bars_dict[val] = bars
        
        # Add percentage labels
        if show_percentages:
            for j, (bar, height) in enumerate(zip(bars, heights)):
                if height >= percentage_threshold:
                    ax.text(bar.get_x() + bar.get_width() / 2,
                           bottom[j] + height / 2,
                           f'{height:.1f}%',
                           ha='center', va='center',
                           fontsize=percentage_fontsize,
                           color=percentage_color,
                           fontweight='bold')
        
        bottom += heights
    
    # Add sample counts above bars
    if show_counts:
        for j, (x_pos, count) in enumerate(zip(x_positions, counts.values)):
            ax.text(x_pos, 102, count_format.format(n=count),
                   ha='center', va='bottom',
                   fontsize=count_fontsize, fontweight='bold')
    
    # Styling
    ax.set_xticks(x_positions)
    # Use custom x-tick labels if provided, otherwise use auto-generated bin_labels
    _final_xtick_labels = xtick_labels_custom if xtick_labels_custom is not None else bin_labels
    _xtick_kw = dict(rotation=xtick_rotation, ha=xtick_ha)
    if xtick_fontsize is not None:
        _xtick_kw['fontsize'] = xtick_fontsize
    if xtick_va is not None:
        _xtick_kw['va'] = xtick_va
    _xtick_kw.update(xtick_extra)
    ax.set_xticklabels(_final_xtick_labels, **_xtick_kw)
    if ytick_fontsize is not None:
        ax.tick_params(axis='y', labelsize=ytick_fontsize)
    ax.set_ylim(0, 110 if show_counts else 105)
    ax.set_xlim(-0.5, n_actual_bins - 0.5)
    
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')
    
    ax.spines['top'].set_visible(False)
    ax.spines['right'].set_visible(False)
    
    # Add horizontal line at 100%
    ax.axhline(y=100, color='gray', linestyle='--', linewidth=0.8, alpha=0.5)
    
    # Legend
    if show_legend:
        legend_kwargs = {
            'title': legend_title,
            'loc': legend_loc,
            'framealpha': 0.9,
            'ncol': legend_ncol
        }
        if legend_bbox:
            legend_kwargs['bbox_to_anchor'] = legend_bbox
        ax.legend(**legend_kwargs)

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    # Create result DataFrame
    result_df = crosstab.copy()
    result_df['count'] = counts
    result_df.index = bin_labels
    result_df.index.name = bin_col + '_bin'

    return fig, ax, result_df


def network_locations_plot(network=None,
                           network_links_gdf=None,
                           network_nodes_gdf=None,
                           locations_gdf=None,
                           type_col='type',
                           figure_size=(12, 12),
                           dpi=350,
                           figure_folder=None,
                           filename=None,
                           xlim=None,
                           ylim=None,
                           **kwargs
                           ):
    """
    Visualize carrier and receiver locations on a transportation network.
    
    This function plots network links and nodes with carrier/receiver locations overlaid.
    Supports both matsim network objects and GeoDataFrames directly.
    
    Args:
        network: MATSim network object from matsim.read_network() (optional).
            If provided, will extract links and nodes from it.
        network_links_gdf: GeoDataFrame of network links (optional if network is provided).
        network_nodes_gdf: GeoDataFrame of network nodes (optional if network is provided).
        locations_gdf: GeoDataFrame containing carrier and receiver locations.
            Must have a geometry column and a type column to distinguish carriers/receivers.
        type_col: Column name in locations_gdf that identifies point type (default: 'type').
            Expected values: 'carrier', 'receiver', 'depot', etc.
        figure_size: Figure size as tuple (width, height) (default: (12, 12))
        dpi: Figure resolution (default: 350)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        xlim: Tuple (xmin, xmax) to limit x-axis range (default: None, auto)
        ylim: Tuple (ymin, ymax) to limit y-axis range (default: None, auto)

    Keyword Args:
        # Network display options
        show_links: Whether to show network links (default: True)
        show_nodes: Whether to show network nodes (default: True)
        use_arrows: Whether to draw links as arrows (default: True)
        link_color: Color for network links (default: 'gray')
        link_linewidth: Line width for links (default: 0.8)
        link_alpha: Transparency for links (default: 0.7)
        arrow_scale: Arrow head scale (default: 8)
        arrow_shrink: Arrow shrink from endpoints (default: 3)
        node_color: Color for network nodes (default: 'lightgray')
        node_size: Size for network nodes (default: 10)
        node_alpha: Transparency for nodes (default: 1.0)
        
        # Central region display options
        show_central_region: Whether to show central region rectangle (default: False)
        central_region_bounds: Tuple (xmin, ymin, width, height) for central region (default: (2000, 2000, 5000, 5000))
        central_region_color: Color for central region (default: '#4a90d9')
        central_region_alpha: Transparency for central region (default: 0.08)
        central_region_edge_alpha: Edge transparency (default: 1.0)
        central_region_linestyle: Line style (default: '--')
        central_region_linewidth: Line width (default: 2)
        central_region_label: Label for legend (default: 'Central Region')
        show_central_shadow: Whether to show shadow effect (default: True)
        shadow_offset: Shadow offset (x, y) (default: (50, -50))
        shadow_color: Shadow color (default: 'lightblue')
        shadow_alpha: Shadow transparency (default: 0.15)
        
        # Carrier display options
        carrier_color: Color for carrier/depot points (default: 'red')
        carrier_size: Marker size for carriers (default: 200)
        carrier_marker: Marker style for carriers (default: '*')
        carrier_alpha: Transparency for carriers (default: 1.0)
        carrier_edgecolor: Edge color for carrier markers (default: 'darkred')
        carrier_linewidth: Edge line width for carriers (default: 1)
        carrier_label: Label for carrier legend (default: 'Depot')
        carrier_types: List of type values to treat as carriers (default: ['carrier', 'depot'])
        
        # Receiver display options
        receiver_color: Color for receiver points (default: 'orange')
        receiver_size: Marker size for receivers (default: 100)
        receiver_marker: Marker style for receivers (default: 'o')
        receiver_alpha: Transparency for receivers (default: 0.7)
        receiver_edgecolor: Edge color for receiver markers (default: 'darkorange')
        receiver_linewidth: Edge line width for receivers (default: 1)
        receiver_label: Label for receiver legend (default: 'Receiver')
        receiver_types: List of type values to treat as receivers (default: ['receiver'])
        
        # Color grouping for receivers (optional)
        receiver_color_col: Column name to color receivers by value (default: None)
        receiver_cmap: Colormap for receiver coloring (default: 'viridis')
        receiver_cmap_vmin: Min value for colormap (default: auto)
        receiver_cmap_vmax: Max value for colormap (default: auto)
        show_colorbar: Whether to show colorbar when receiver_color_col is set (default: True)
        colorbar_label: Label for colorbar (default: receiver_color_col)
        
        # Size grouping for receivers (optional)
        receiver_size_col: Column name to size receivers by value (default: None)
        receiver_size_min: Min marker size (default: 30)
        receiver_size_max: Max marker size (default: 200)
        
        # Location positioning options
        use_link_midpoint: Whether to position locations at link midpoints (default: True).
            If True and link_id_col exists in locations_gdf, locations will be plotted
            at the midpoint of their associated link instead of their geometry coordinates.
        link_id_col: Column name containing link IDs for midpoint positioning (default: 'link_id')
        
        # Labels and display options
        title: Plot title (default: '')
        title_size: Font size for title (default: 14)
        xlabel: X-axis label (default: 'X Coordinate (m)')
        ylabel: Y-axis label (default: 'Y Coordinate (m)')
        label_size: Font size for axis labels (default: 12)
        hide_labels: Whether to hide axis labels (default: False)
        hide_axis: Whether to hide axis entirely (default: False)
        
        # Legend options
        show_legend: Whether to show legend (default: True)
        legend_loc: Location for legend (default: 'upper right')
        legend_fontsize: Font size for legend (default: 10)
        legend_framealpha: Legend background alpha (default: 0.9)
        
        # Grid and style options
        show_grid: Whether to show grid (default: True)
        grid_alpha: Grid transparency (default: 0.3)
        equal_aspect: Whether to use equal aspect ratio (default: True)
        
        show: Whether to display the plot (default: True)
        ax: Existing matplotlib axis to plot on (default: None, creates new figure)

    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object

    Examples:
        # Using matsim network object
        network = matsim.read_network('output_network.xml.gz')
        network_locations_plot(network=network, locations_gdf=receivers_gdf)
        
        # With central region highlight
        network_locations_plot(network=network, locations_gdf=receivers_gdf,
                              show_central_region=True,
                              central_region_bounds=(2000, 2000, 5000, 5000))
        
        # With axis limits
        network_locations_plot(network=network, locations_gdf=receivers_gdf,
                              xlim=(1000, 8000), ylim=(1000, 8000))
    """
    import geopandas as gpd
    from matplotlib.patches import Rectangle, FancyBboxPatch, FancyArrowPatch
    
    # Extract kwargs with defaults
    # Network options
    show_links = kwargs.get('show_links', True)
    show_nodes = kwargs.get('show_nodes', True)
    use_arrows = kwargs.get('use_arrows', True)
    link_color = kwargs.get('link_color', 'gray')
    link_linewidth = kwargs.get('link_linewidth', 0.8)
    link_alpha = kwargs.get('link_alpha', 0.7)
    arrow_scale = kwargs.get('arrow_scale', 8)
    arrow_shrink = kwargs.get('arrow_shrink', 3)
    node_color = kwargs.get('node_color', 'lightgray')
    node_size = kwargs.get('node_size', 10)
    node_alpha = kwargs.get('node_alpha', 1.0)
    
    # Central region options
    show_central_region = kwargs.get('show_central_region', False)
    central_region_bounds = kwargs.get('central_region_bounds', (2000, 2000, 5000, 5000))
    central_region_color = kwargs.get('central_region_color', '#4a90d9')
    central_region_alpha = kwargs.get('central_region_alpha', 0.08)
    central_region_edge_alpha = kwargs.get('central_region_edge_alpha', 1.0)
    central_region_linestyle = kwargs.get('central_region_linestyle', '--')
    central_region_linewidth = kwargs.get('central_region_linewidth', 2)
    central_region_label = kwargs.get('central_region_label', 'Central Region')
    show_central_shadow = kwargs.get('show_central_shadow', True)
    shadow_offset = kwargs.get('shadow_offset', (50, -50))
    shadow_color = kwargs.get('shadow_color', 'lightblue')
    shadow_alpha = kwargs.get('shadow_alpha', 0.15)
    
    # Carrier options
    carrier_color = kwargs.get('carrier_color', 'red')
    carrier_size = kwargs.get('carrier_size', 200)
    carrier_marker = kwargs.get('carrier_marker', '*')
    carrier_alpha = kwargs.get('carrier_alpha', 1.0)
    carrier_edgecolor = kwargs.get('carrier_edgecolor', 'darkred')
    carrier_linewidth = kwargs.get('carrier_linewidth', 1)
    carrier_label = kwargs.get('carrier_label', 'Depot')
    carrier_types = kwargs.get('carrier_types', ['carrier', 'depot'])
    
    # Receiver options
    receiver_color = kwargs.get('receiver_color', 'orange')
    receiver_size = kwargs.get('receiver_size', 100)
    receiver_marker = kwargs.get('receiver_marker', 'o')
    receiver_alpha = kwargs.get('receiver_alpha', 0.7)
    receiver_edgecolor = kwargs.get('receiver_edgecolor', 'darkorange')
    receiver_linewidth = kwargs.get('receiver_linewidth', 1)
    receiver_label = kwargs.get('receiver_label', 'Receiver')
    receiver_types = kwargs.get('receiver_types', ['receiver'])
    
    # Receiver color grouping
    receiver_color_col = kwargs.get('receiver_color_col', None)
    receiver_cmap = kwargs.get('receiver_cmap', 'viridis')
    receiver_cmap_vmin = kwargs.get('receiver_cmap_vmin', None)
    receiver_cmap_vmax = kwargs.get('receiver_cmap_vmax', None)
    show_colorbar = kwargs.get('show_colorbar', True)
    colorbar_label = kwargs.get('colorbar_label', receiver_color_col)
    
    # Receiver size grouping
    receiver_size_col = kwargs.get('receiver_size_col', None)
    receiver_size_min = kwargs.get('receiver_size_min', 30)
    receiver_size_max = kwargs.get('receiver_size_max', 200)
    
    # Labels and display
    title = kwargs.get('title', '')
    title_size = kwargs.get('title_size', 14)
    xlabel = kwargs.get('xlabel', 'X Coordinate (m)')
    ylabel = kwargs.get('ylabel', 'Y Coordinate (m)')
    label_size = kwargs.get('label_size', 12)
    hide_labels = kwargs.get('hide_labels', False)
    hide_axis = kwargs.get('hide_axis', False)
    
    # Legend options
    show_legend = kwargs.get('show_legend', True)
    legend_loc = kwargs.get('legend_loc', 'upper right')
    legend_fontsize = kwargs.get('legend_fontsize', 10)
    legend_framealpha = kwargs.get('legend_framealpha', 0.9)
    
    # Grid options
    show_grid = kwargs.get('show_grid', True)
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    equal_aspect = kwargs.get('equal_aspect', True)
    
    show = kwargs.get('show', True)
    ax_input = kwargs.get('ax', None)

    # Process network input
    links_gdf = network_links_gdf
    nodes_gdf = network_nodes_gdf
    links_df = None
    nodes_df = None
    
    if network is not None:
        # Extract from matsim network object
        if hasattr(network, 'links'):
            links_df = network.links
        if hasattr(network, 'nodes'):
            nodes_df = network.nodes
    
    # Build node_coords dictionary for arrow plotting
    node_coords = {}
    if nodes_df is not None and 'x' in nodes_df.columns and 'y' in nodes_df.columns:
        if 'node_id' in nodes_df.columns:
            for _, row in nodes_df.iterrows():
                node_coords[row['node_id']] = (row['x'], row['y'])
        else:
            for idx, row in nodes_df.iterrows():
                node_coords[idx] = (row['x'], row['y'])
    
    # Convert nodes to GeoDataFrame if needed
    if nodes_gdf is None and nodes_df is not None:
        from shapely.geometry import Point
        if isinstance(nodes_df, gpd.GeoDataFrame):
            nodes_gdf = nodes_df
        elif 'geometry' in nodes_df.columns:
            nodes_gdf = gpd.GeoDataFrame(nodes_df, geometry='geometry')
        elif 'x' in nodes_df.columns and 'y' in nodes_df.columns:
            nodes_gdf = gpd.GeoDataFrame(
                nodes_df,
                geometry=[Point(x, y) for x, y in zip(nodes_df['x'], nodes_df['y'])]
            )
    
    # Convert links to GeoDataFrame if needed (for non-arrow mode)
    if links_gdf is None and links_df is not None:
        from shapely.geometry import LineString
        if isinstance(links_df, gpd.GeoDataFrame):
            links_gdf = links_df
        elif 'geometry' in links_df.columns:
            links_gdf = gpd.GeoDataFrame(links_df, geometry='geometry')
        elif node_coords and 'from_node' in links_df.columns and 'to_node' in links_df.columns:
            geometries = []
            valid_indices = []
            for idx, row in links_df.iterrows():
                from_node = row['from_node']
                to_node = row['to_node']
                if from_node in node_coords and to_node in node_coords:
                    from_coord = node_coords[from_node]
                    to_coord = node_coords[to_node]
                    geometries.append(LineString([from_coord, to_coord]))
                    valid_indices.append(idx)
            
            if geometries:
                links_gdf = gpd.GeoDataFrame(
                    links_df.loc[valid_indices],
                    geometry=geometries
                )
        elif 'from_x' in links_df.columns and 'from_y' in links_df.columns and \
             'to_x' in links_df.columns and 'to_y' in links_df.columns:
            from shapely.geometry import LineString
            geometries = [
                LineString([(row['from_x'], row['from_y']), (row['to_x'], row['to_y'])])
                for _, row in links_df.iterrows()
            ]
            links_gdf = gpd.GeoDataFrame(links_df, geometry=geometries)

    # Create figure and axis
    if ax_input is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        ax = ax_input
        fig = ax.figure

    # ========== Draw central region rectangle ==========
    if show_central_region:
        cr_x, cr_y, cr_w, cr_h = central_region_bounds
        
        # Shadow rectangle (slightly offset)
        if show_central_shadow:
            shadow = FancyBboxPatch(
                (cr_x + shadow_offset[0], cr_y + shadow_offset[1]), cr_w, cr_h,
                boxstyle="round,pad=0.02",
                facecolor=shadow_color, alpha=shadow_alpha,
                edgecolor='None',
                zorder=0
            )
            ax.add_patch(shadow)
        
        # Main rectangle for central region
        central_region = Rectangle(
            (cr_x, cr_y), cr_w, cr_h,
            linewidth=central_region_linewidth,
            edgecolor=central_region_color,
            facecolor=central_region_color,
            alpha=central_region_alpha,
            linestyle=central_region_linestyle,
            zorder=0.5,
            label=central_region_label
        )
        ax.add_patch(central_region)

    # ========== Plot network links ==========
    if show_links and links_df is not None and use_arrows:
        # Draw links as directed arrows
        for _, link in links_df.iterrows():
            from_node = link['from_node']
            to_node = link['to_node']
            if from_node in node_coords and to_node in node_coords:
                x1, y1 = node_coords[from_node]
                x2, y2 = node_coords[to_node]
                arrow = FancyArrowPatch(
                    (x1, y1), (x2, y2),
                    arrowstyle='->',
                    mutation_scale=arrow_scale,
                    color=link_color,
                    linewidth=link_linewidth,
                    alpha=link_alpha,
                    shrinkA=arrow_shrink,
                    shrinkB=arrow_shrink,
                    zorder=1
                )
                ax.add_patch(arrow)
    elif show_links and links_gdf is not None:
        # Fallback to simple lines
        links_gdf.plot(ax=ax, color=link_color, linewidth=link_linewidth,
                       alpha=link_alpha, zorder=1)

    # ========== Plot network nodes ==========
    if show_nodes and nodes_df is not None:
        ax.scatter(nodes_df['x'], nodes_df['y'], c=node_color, s=node_size,
                   zorder=1.5, alpha=node_alpha, label='Network Nodes')
    elif show_nodes and nodes_gdf is not None:
        nodes_gdf.plot(ax=ax, color=node_color, markersize=node_size,
                       alpha=node_alpha, zorder=1.5)

    # ========== Plot locations ==========
    if locations_gdf is not None and len(locations_gdf) > 0:
        # Separate carriers and receivers
        carriers_mask = locations_gdf[type_col].isin(carrier_types)
        receivers_mask = locations_gdf[type_col].isin(receiver_types)
        
        carriers_gdf = locations_gdf[carriers_mask].copy()
        receivers_gdf = locations_gdf[receivers_mask].copy()
        
        # Build link midpoint lookup if we have link_id column and network data
        # This allows plotting locations at link midpoints instead of node positions
        link_midpoints = {}
        link_id_col = kwargs.get('link_id_col', 'link_id')
        use_link_midpoint = kwargs.get('use_link_midpoint', True)
        
        if use_link_midpoint and links_df is not None and node_coords:
            # Build link_id -> midpoint mapping
            for _, link in links_df.iterrows():
                from_node = link['from_node']
                to_node = link['to_node']
                if from_node in node_coords and to_node in node_coords:
                    x1, y1 = node_coords[from_node]
                    x2, y2 = node_coords[to_node]
                    mid_x = (x1 + x2) / 2
                    mid_y = (y1 + y2) / 2
                    # Use link_id if available, otherwise use index
                    if 'link_id' in link.index:
                        link_midpoints[link['link_id']] = (mid_x, mid_y)
                    else:
                        link_midpoints[link.name] = (mid_x, mid_y)
        
        # Helper function to get coordinates (from link midpoint or geometry)
        def get_plot_coords(gdf, link_id_col, link_midpoints):
            """Get x, y coordinates for plotting - use link midpoints if available"""
            if use_link_midpoint and link_id_col in gdf.columns and link_midpoints:
                x_coords = []
                y_coords = []
                for _, row in gdf.iterrows():
                    lid = row[link_id_col]
                    if lid in link_midpoints:
                        x_coords.append(link_midpoints[lid][0])
                        y_coords.append(link_midpoints[lid][1])
                    else:
                        # Fallback to geometry
                        x_coords.append(row.geometry.x)
                        y_coords.append(row.geometry.y)
                return np.array(x_coords), np.array(y_coords)
            else:
                # Use geometry coordinates
                return gdf.geometry.x.values, gdf.geometry.y.values
        
        # Plot receivers first (below carriers)
        if len(receivers_gdf) > 0:
            rec_x, rec_y = get_plot_coords(receivers_gdf, link_id_col, link_midpoints)
            
            # Determine sizes
            if receiver_size_col is not None and receiver_size_col in receivers_gdf.columns:
                size_values = receivers_gdf[receiver_size_col].values
                size_vmin = size_values.min()
                size_vmax = size_values.max()
                if size_vmax > size_vmin:
                    normalized = (size_values - size_vmin) / (size_vmax - size_vmin)
                else:
                    normalized = np.ones(len(size_values)) * 0.5
                r_sizes = receiver_size_min + normalized * (receiver_size_max - receiver_size_min)
            else:
                r_sizes = receiver_size
            
            # Determine colors
            if receiver_color_col is not None and receiver_color_col in receivers_gdf.columns:
                c_values = receivers_gdf[receiver_color_col].values
                if receiver_cmap_vmin is None:
                    receiver_cmap_vmin = np.nanmin(c_values)
                if receiver_cmap_vmax is None:
                    receiver_cmap_vmax = np.nanmax(c_values)
                
                scatter = ax.scatter(rec_x, rec_y,
                                    c=c_values, cmap=receiver_cmap,
                                    vmin=receiver_cmap_vmin, vmax=receiver_cmap_vmax,
                                    s=r_sizes, marker=receiver_marker,
                                    alpha=receiver_alpha,
                                    edgecolors=receiver_edgecolor,
                                    linewidths=receiver_linewidth,
                                    zorder=3,
                                    label=f'{receiver_label} (n={len(receivers_gdf)})')
                
                if show_colorbar:
                    cbar = fig.colorbar(scatter, ax=ax, shrink=0.6, pad=0.02)
                    if colorbar_label:
                        cbar.set_label(colorbar_label, fontsize=10)
            else:
                ax.scatter(rec_x, rec_y,
                          c=receiver_color, s=r_sizes, marker=receiver_marker,
                          alpha=receiver_alpha,
                          edgecolors=receiver_edgecolor,
                          linewidths=receiver_linewidth,
                          zorder=3,
                          label=f'{receiver_label} (n={len(receivers_gdf)})')
        
        # Plot carriers on top
        if len(carriers_gdf) > 0:
            car_x, car_y = get_plot_coords(carriers_gdf, link_id_col, link_midpoints)
            ax.scatter(car_x, car_y,
                      c=carrier_color, s=carrier_size, marker=carrier_marker,
                      alpha=carrier_alpha,
                      edgecolors=carrier_edgecolor,
                      linewidths=carrier_linewidth,
                      zorder=4,
                      label=f'{carrier_label} (n={len(carriers_gdf)})')

    # ========== Axis limits ==========
    if xlim is not None:
        ax.set_xlim(xlim)
    if ylim is not None:
        ax.set_ylim(ylim)

    # ========== Styling ==========
    if title:
        ax.set_title(title, fontsize=title_size)
    
    if not hide_labels:
        ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
        
    
    if hide_axis:
        ax.axis('off')
    
    if show_grid:
        ax.grid(True, alpha=grid_alpha)
    
    if equal_aspect:
        ax.set_aspect('equal')
    
    # Legend
    if show_legend:
        ax.legend(loc=legend_loc, fontsize=legend_fontsize,
                 framealpha=legend_framealpha)

    plt.tight_layout()

    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path),
                    bbox_inches='tight',
                    pad_inches=0.05,
                    dpi=dpi)

    if show:
        plt.show()

    return fig, ax


def nested_donut_chart(
    data_dict: dict,
    group_col: str,
    count_col: str = None,
    agg_func: str = 'count',
    inner_colors: list = None,
    outer_colors: dict = None,
    outer_cmap: str = 'Blues',
    outer_color_list: list = None,
    figsize: tuple = (10, 10),
    hole_radius: float = 0.2,
    inner_radius: float = 0.4,
    outer_width: float = 0.3,
    inner_width: float = 0.3,
    title: str = None,
    title_size: int = 14,
    inner_label_size: int = 11,
    outer_label_size: int = 9,
    show_inner_ring: bool = True,
    show_inner_labels: bool = True,
    show_outer_labels: bool = True,
    show_outer_values: bool = True,
    show_inner_pct: bool = True,
    show_outer_pct: bool = True,
    show_outer_count: bool = False,
    inner_label_format: str = '{label}',
    outer_label_format: str = '{value}',
    pct_format: str = '{pct:.1f}%',
    legend_loc: str = 'upper right',
    legend_fontsize: int = 10,
    show_legend: bool = True,
    startangle: float = 90,
    edgecolor: str = 'white',
    edge_linewidth: float = 1.5,
    center_text: str = None,
    center_text_size: int = 12,
    transparent_bg: bool = False,
    ax: object = None,
    figure_folder: str = None,
    filename: str = None,
    dpi: int = 300,
    show: bool = True
):
    """
    Create a nested (hierarchical) donut chart for visualizing grouped frequency data.
    
    The inner ring is divided equally based on the number of input DataFrames/scenarios,
    and the outer ring shows the distribution of a grouped variable (e.g., fleet_size counts)
    for each scenario.
    
    Args:
        data_dict: Dictionary where keys are scenario labels and values are DataFrames.
            Example: {'Center-Clustered': df1, 'Center-Dispersed': df2, ...}
        group_col: Column name to group by (e.g., 'final_fleet_size').
        count_col: Column to count/aggregate. If None, counts rows.
        agg_func: Aggregation function ('count', 'sum', 'mean'). Default: 'count'.
        inner_colors: List of colors for inner ring segments (one per scenario).
            If None, uses a default palette.
        outer_colors: Dict mapping group values to colors for outer ring.
            If None, uses gradient based on outer_cmap or outer_color_list.
        outer_cmap: Colormap name for outer ring gradient (default: 'Blues').
            Ignored if outer_color_list is provided.
        outer_color_list: List of colors for outer ring segments by group value.
            If provided, overrides outer_cmap. Colors are assigned in sorted order of group values.
        figsize: Figure size (width, height).
        hole_radius: Radius of the center hole (empty space in the middle). Default: 0.2.
        inner_radius: Radius of the inner ring's inner edge. This is calculated from
            hole_radius if not explicitly larger. Default: 0.4.
        outer_width: Width of the outer ring.
        inner_width: Width of the inner ring.
        title: Chart title.
        title_size: Font size for title.
        inner_label_size: Font size for inner ring labels.
        outer_label_size: Font size for outer ring labels.
        show_inner_ring: Whether to show the inner ring at all. Default: True.
        show_inner_labels: Whether to show labels on inner ring.
        show_outer_labels: Whether to show labels on outer ring.
        show_outer_values: Whether to show group values on outer ring labels. Default: True.
        show_inner_pct: Whether to show percentage on inner ring.
        show_outer_pct: Whether to show percentage on outer ring.
        show_outer_count: Whether to show count on outer ring labels.
        inner_label_format: Format string for inner labels. Use {label}, {pct}.
        outer_label_format: Format string for outer labels. Use {value}, {count}, {pct}.
        pct_format: Format string for percentage display.
        legend_loc: Legend location.
        legend_fontsize: Font size for legend.
        show_legend: Whether to show legend.
        startangle: Starting angle for the chart (default: 90, top).
        edgecolor: Edge color between segments.
        edge_linewidth: Line width of edges.
        center_text: Text to display in the center of the donut.
        center_text_size: Font size for center text.
        ax: Optional matplotlib axes.
        figure_folder: Folder to save figure.
        filename: Filename for saving.
        dpi: Resolution for saving.
        show: Whether to display the figure.
    
    Returns:
        Tuple of (fig, ax) matplotlib objects.
    
    Examples:
        # Basic usage with 4 scenarios
        data_dict = {
            'Center-Clustered': ins_center_clustered_specific_anls_df,
            'Center-Dispersed': ins_center_dispersed_specific_anls_df,
            'Outside-Clustered': ins_outside_clustered_specific_anls_df,
            'Outside-Dispersed': ins_outside_dispersed_specific_anls_df
        }
        
        fig, ax = nested_donut_chart(
            data_dict,
            group_col='final_fleet_size',
            title='Fleet Size Distribution by Scenario'
        )
        
        # With custom colors
        fig, ax = nested_donut_chart(
            data_dict,
            group_col='final_fleet_size',
            inner_colors=['#e41a1c', '#377eb8', '#4daf4a', '#984ea3'],
            outer_cmap='viridis'
        )
    """
    import matplotlib.patches as mpatches
    from matplotlib.cm import get_cmap
    
    if ax is None:
        fig, ax = plt.subplots(figsize=figsize)
    else:
        fig = ax.figure
    
    n_scenarios = len(data_dict)
    
    # Default inner colors
    if inner_colors is None:
        default_palette = ['#e41a1c', '#377eb8', '#4daf4a', '#984ea3', 
                          '#ff7f00', '#ffff33', '#a65628', '#f781bf']
        inner_colors = default_palette[:n_scenarios]
    
    # Prepare data for each scenario
    scenario_data = {}
    all_group_values = set()
    
    for label, df in data_dict.items():
        if count_col is None:
            # Count rows per group
            grouped = df.groupby(group_col).size().reset_index(name='count')
            grouped.columns = [group_col, 'value']
        else:
            if agg_func == 'count':
                grouped = df.groupby(group_col).agg({count_col: 'count'}).reset_index()
            elif agg_func == 'sum':
                grouped = df.groupby(group_col).agg({count_col: 'sum'}).reset_index()
            elif agg_func == 'mean':
                grouped = df.groupby(group_col).agg({count_col: 'mean'}).reset_index()
            else:
                grouped = df.groupby(group_col).agg({count_col: agg_func}).reset_index()
            grouped.columns = [group_col, 'value']
        
        scenario_data[label] = grouped
        all_group_values.update(grouped[group_col].unique())
    
    # Sort group values
    all_group_values = sorted(all_group_values)
    
    # Generate outer colors if not provided
    if outer_colors is None:
        n_values = len(all_group_values)
        if outer_color_list is not None:
            # Use custom color list
            if len(outer_color_list) < n_values:
                # Cycle through colors if not enough
                outer_color_list = (outer_color_list * ((n_values // len(outer_color_list)) + 1))[:n_values]
            outer_colors = {v: outer_color_list[i] for i, v in enumerate(all_group_values)}
        else:
            # Use colormap
            cmap = get_cmap(outer_cmap)
            outer_colors = {v: cmap(0.3 + 0.6 * i / max(n_values - 1, 1)) 
                           for i, v in enumerate(all_group_values)}
    
    # Use hole_radius to control the center empty space
    # inner_radius parameter is deprecated in favor of hole_radius
    actual_inner_radius = hole_radius
    
    # Calculate total for each scenario (for percentage)
    scenario_totals = {label: grouped['value'].sum() 
                       for label, grouped in scenario_data.items()}
    grand_total = sum(scenario_totals.values())
    
    # ----- Inner Ring (equal segments for each scenario) -----
    inner_sizes = [1] * n_scenarios  # Equal sizes
    inner_labels = list(data_dict.keys())
    
    # Calculate inner ring radii
    # The inner ring starts at hole_radius and extends by inner_width
    inner_outer_radius = actual_inner_radius + inner_width
    
    # Create inner ring (only if show_inner_ring is True)
    if show_inner_ring:
        inner_wedges, inner_texts = ax.pie(
            inner_sizes,
            radius=inner_outer_radius,
            colors=inner_colors,
            startangle=startangle,
            wedgeprops=dict(width=inner_width, edgecolor=edgecolor, linewidth=edge_linewidth),
            labels=None  # We'll add labels manually for better control
        )
    
    # Add inner labels
    if show_inner_ring and show_inner_labels:
        angle_per_scenario = 360 / n_scenarios
        for i, label in enumerate(inner_labels):
            # Calculate angle for label placement
            # matplotlib pie draws counter-clockwise from startangle
            # So we ADD angles for subsequent wedges
            angle = startangle + i * angle_per_scenario + angle_per_scenario / 2
            angle_rad = np.radians(angle)
            
            # Position label at the middle of the inner ring
            label_radius = actual_inner_radius + inner_width / 2
            x = label_radius * np.cos(angle_rad)
            y = label_radius * np.sin(angle_rad)
            
            # Format label
            pct = scenario_totals[label] / grand_total * 100 if grand_total > 0 else 0
            if show_inner_pct:
                label_text = f"{label}\n{pct_format.format(pct=pct)}"
            else:
                label_text = inner_label_format.format(label=label)
            
            ax.annotate(
                label_text,
                xy=(x, y),
                ha='center',
                va='center',
                fontsize=inner_label_size,
                fontweight='bold',
                color='white' if sum(mcolors.to_rgb(inner_colors[i])) < 1.5 else 'black'
            )
    
    # ----- Outer Ring (proportional segments based on group values) -----
    outer_sizes = []
    outer_colors_list = []
    outer_labels_data = []
    
    # Calculate the angle each scenario occupies
    angle_per_scenario = 360 / n_scenarios
    
    # IMPORTANT: Iterate scenarios and group values in consistent order
    # Each scenario should have the same group value order for proper color alignment
    for i, (label, grouped) in enumerate(scenario_data.items()):
        scenario_total = scenario_totals[label]
        
        # Create a lookup dict for this scenario's counts
        scenario_counts = grouped.set_index(group_col)['value'].to_dict()
        
        # Iterate through ALL group values in sorted order (consistent across scenarios)
        for group_value in all_group_values:
            count = scenario_counts.get(group_value, 0)
            
            # Size proportional within the scenario's segment
            # Each scenario gets equal arc, subdivided by group proportions
            if scenario_total > 0:
                proportion_in_scenario = count / scenario_total
            else:
                proportion_in_scenario = 0
            
            # Size is proportion of the scenario's equal share
            size = proportion_in_scenario * (1 / n_scenarios)
            outer_sizes.append(size)
            
            # Color based on group value - now guaranteed to match legend
            outer_colors_list.append(outer_colors.get(group_value, '#cccccc'))
            
            # Store data for labels
            outer_labels_data.append({
                'scenario': label,
                'group_value': group_value,
                'count': count,
                'pct_in_scenario': proportion_in_scenario * 100,
                'pct_total': (count / grand_total * 100) if grand_total > 0 else 0
            })
    
    # Calculate outer ring radii
    outer_inner_radius = inner_outer_radius
    outer_outer_radius = outer_inner_radius + outer_width
    
    # Create outer ring
    if sum(outer_sizes) > 0:
        outer_wedges, _ = ax.pie(
            outer_sizes,
            radius=outer_outer_radius,
            colors=outer_colors_list,
            startangle=startangle,
            wedgeprops=dict(width=outer_width, edgecolor=edgecolor, linewidth=edge_linewidth * 0.5),
            labels=None
        )
        
        # Add outer labels
        if show_outer_labels:
            cumulative_angle = startangle
            for j, (size, data) in enumerate(zip(outer_sizes, outer_labels_data)):
                # Calculate angle (counter-clockwise from startangle)
                segment_angle = size * 360
                angle = cumulative_angle + segment_angle / 2
                
                # Only draw label if segment is large enough and has data
                if size >= 0.015 and data['count'] > 0:
                    angle_rad = np.radians(angle)
                    
                    label_radius = outer_inner_radius + outer_width / 2
                    x = label_radius * np.cos(angle_rad)
                    y = label_radius * np.sin(angle_rad)
                    
                    # Format label
                    label_parts = []
                    if show_outer_values:
                        label_parts.append(str(data['group_value']))
                    if show_outer_count:
                        label_parts.append(f"n={int(data['count'])}")
                    if show_outer_pct:
                        label_parts.append(pct_format.format(pct=data['pct_in_scenario']))
                    
                    label_text = '\n'.join(label_parts) if label_parts else str(data['group_value'])
                    
                    ax.annotate(
                        label_text,
                        xy=(x, y),
                        ha='center',
                        va='center',
                        fontsize=outer_label_size,
                        color='white' if sum(mcolors.to_rgb(outer_colors_list[j])) < 1.5 else 'black'
                    )
                
                # Always update cumulative angle (counter-clockwise = add)
                cumulative_angle += segment_angle
    
    # Center text
    if center_text:
        ax.text(0, 0, center_text, ha='center', va='center', 
                fontsize=center_text_size, fontweight='bold')
    
    # Legend for group values
    if show_legend:
        legend_patches = [mpatches.Patch(color=outer_colors[v], label=f'{group_col}={v}') 
                         for v in all_group_values]
        ax.legend(handles=legend_patches, loc=legend_loc, fontsize=legend_fontsize)
    
    # Title
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold', pad=20)
    
    ax.set_aspect('equal')
    
    # Set transparent background if requested
    if transparent_bg:
        fig.patch.set_alpha(0)
        ax.patch.set_alpha(0)
    
    plt.tight_layout()
    
    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight', pad_inches=0.1, dpi=dpi,
                    transparent=transparent_bg)
    
    if show:
        plt.show()
    
    return fig, ax


def nested_donut_chart_simple(
    data_dict: dict,
    group_col: str,
    inner_colors: list = None,
    outer_cmap: str = 'Blues',
    figsize: tuple = (10, 10),
    inner_radius: float = 0.35,
    outer_width: float = 0.35,
    inner_width: float = 0.25,
    title: str = None,
    show_values: bool = True,
    show_legend: bool = True,
    legend_title: str = None,
    startangle: float = 90,
    edgecolor: str = 'white',
    ax: object = None,
    figure_folder: str = None,
    filename: str = None,
    dpi: int = 300,
    show: bool = True
):
    """
    Simplified version of nested donut chart with cleaner defaults.
    
    Inner ring: Shows scenario labels (equal segments)
    Outer ring: Shows distribution of group values within each scenario
    
    Args:
        data_dict: Dict of {scenario_label: DataFrame}
        group_col: Column to group by (e.g., 'final_fleet_size')
        inner_colors: Colors for scenarios
        outer_cmap: Colormap for group values
        figsize: Figure size
        inner_radius: Inner donut hole radius
        outer_width: Width of outer ring
        inner_width: Width of inner ring
        title: Chart title
        show_values: Show group values on outer ring
        show_legend: Show legend for group values
        legend_title: Title for legend
        startangle: Starting angle
        edgecolor: Edge color
        ax: Matplotlib axes
        figure_folder: Save folder
        filename: Save filename
        dpi: Save resolution
        show: Display figure
    
    Returns:
        (fig, ax) tuple
    
    Example:
        data_dict = {
            'Scenario A': df_a,
            'Scenario B': df_b,
            'Scenario C': df_c
        }
        fig, ax = nested_donut_chart_simple(data_dict, 'fleet_size')
    """
    import matplotlib.patches as mpatches
    from matplotlib.cm import get_cmap
    
    if ax is None:
        fig, ax = plt.subplots(figsize=figsize)
    else:
        fig = ax.figure
    
    n_scenarios = len(data_dict)
    
    # Default colors
    if inner_colors is None:
        inner_colors = sns.color_palette('husl', n_scenarios)
    
    # Collect all unique group values
    all_values = set()
    scenario_groups = {}
    
    for label, df in data_dict.items():
        counts = df.groupby(group_col).size()
        scenario_groups[label] = counts
        all_values.update(counts.index)
    
    all_values = sorted(all_values)
    n_values = len(all_values)
    
    # Generate outer colors
    cmap = get_cmap(outer_cmap)
    value_colors = {v: cmap(0.25 + 0.65 * i / max(n_values - 1, 1)) 
                    for i, v in enumerate(all_values)}
    
    # Build outer ring data
    outer_sizes = []
    outer_colors_list = []
    outer_labels = []
    
    for label, counts in scenario_groups.items():
        total = counts.sum()
        for value in all_values:
            count = counts.get(value, 0)
            # Proportion of total across all scenarios
            prop = count / sum(sum(g) for g in scenario_groups.values()) if sum(sum(g) for g in scenario_groups.values()) > 0 else 0
            outer_sizes.append(prop)
            outer_colors_list.append(value_colors[value])
            outer_labels.append({'value': value, 'count': count})
    
    # Inner ring (equal segments)
    inner_sizes = [1] * n_scenarios
    
    # Plot inner ring
    inner_wedges, _ = ax.pie(
        inner_sizes,
        radius=inner_radius + inner_width,
        colors=inner_colors,
        startangle=startangle,
        wedgeprops=dict(width=inner_width, edgecolor=edgecolor, linewidth=1.5),
        labels=None
    )
    
    # Add scenario labels to inner ring
    angle_step = 360 / n_scenarios
    for i, label in enumerate(data_dict.keys()):
        angle = np.radians(startangle - i * angle_step - angle_step / 2)
        r = inner_radius + inner_width / 2
        x, y = r * np.cos(angle), r * np.sin(angle)
        color = 'white' if sum(mcolors.to_rgb(inner_colors[i])) < 1.5 else 'black'
        ax.text(x, y, label, ha='center', va='center', fontsize=10, 
                fontweight='bold', color=color)
    
    # Plot outer ring
    if sum(outer_sizes) > 0:
        outer_wedges, _ = ax.pie(
            outer_sizes,
            radius=inner_radius + inner_width + outer_width,
            colors=outer_colors_list,
            startangle=startangle,
            wedgeprops=dict(width=outer_width, edgecolor=edgecolor, linewidth=0.8),
            labels=None
        )
        
        # Add value labels to outer ring
        if show_values:
            cumsum = 0
            total_size = sum(outer_sizes)
            for i, (size, info) in enumerate(zip(outer_sizes, outer_labels)):
                if size < 0.03:  # Skip tiny segments
                    cumsum += size
                    continue
                angle = np.radians(startangle - (cumsum + size/2) * 360 / total_size)
                cumsum += size
                r = inner_radius + inner_width + outer_width / 2
                x, y = r * np.cos(angle), r * np.sin(angle)
                color = 'white' if sum(mcolors.to_rgb(outer_colors_list[i])) < 1.5 else 'black'
                ax.text(x, y, str(info['value']), ha='center', va='center', 
                        fontsize=8, color=color)
    
    # Legend
    if show_legend:
        patches = [mpatches.Patch(color=value_colors[v], label=str(v)) for v in all_values]
        legend_title = legend_title or group_col
        ax.legend(handles=patches, title=legend_title, loc='upper right', fontsize=9)
    
    if title:
        ax.set_title(title, fontsize=14, fontweight='bold', pad=15)
    
    ax.set_aspect('equal')
    plt.tight_layout()
    
    if figure_folder and filename:
        plt.savefig(Path(figure_folder) / filename, bbox_inches='tight', dpi=dpi)
    
    if show:
        plt.show()
    
    return fig, ax


def box_plot(data_list,
             col_name,
             cat_col=None,
             labels=None,
             box_colors=None,
             bg_colors=None,
             figure_size=(10, 6),
             dpi=350,
             figure_folder=None,
             filename=None,
             **kwargs):
    """
    Plot box plots for multiple DataFrames/Series with customizable colors and background regions.
    
    Supports two usage modes:
      1. **List mode** (existing): pass a list of DataFrames/Series as ``data_list``.
      2. **Single-DataFrame mode** (new): pass one DataFrame as ``data_list`` together
         with ``cat_col`` – the name of the categorical column used to split the data
         into groups.  Each unique value in ``cat_col`` becomes one box.
    
    Args:
        data_list: List of DataFrames or Series **or** a single DataFrame.
                   - If a list, each element produces one box; col_name extracts the
                     numeric column from each DataFrame.
                   - If a single DataFrame, ``cat_col`` must be specified; the DataFrame
                     is grouped by ``cat_col`` and each group produces one box.
        col_name: Name of the numeric column to plot.
        cat_col: (Optional) Name of the categorical column used to group the data when
                 ``data_list`` is a single DataFrame.  Ignored when ``data_list`` is a list.
                 The unique values of this column (sorted) are used as box labels unless
                 ``labels`` is explicitly provided.
        labels: List of labels for each box (default: auto-generated).
                - In list mode: 'Box 1', 'Box 2', …
                - In single-DataFrame mode: the sorted unique values of ``cat_col``.
        box_colors: List of colors for each box (default: seaborn color palette)
        bg_colors: List of background colors for each box region (default: None, no background)
                   Set to a list of colors to add alternating/custom background strips behind boxes.
        figure_size: Figure size as tuple (width, height) (default: (10, 6))
        dpi: Figure resolution (default: 350)
        figure_folder: Folder path to save figure (optional)
        filename: Filename to save figure (optional)
        
    Keyword Args:
        # Box appearance
        box_alpha: Alpha for box face color (default: 0.7)
        box_linewidth: Line width for box edges (default: 1.5)
        box_edgecolor: Edge color for boxes (default: 'black')
        use_box_color_for_lines: Whether to use box color for all lines (edges, whiskers, caps, median) (default: False)
                                 When True, each box's color will be applied to all its associated lines.
        
        # Median and mean display
        show_median: Whether to show median line (default: True)
        median_color: Color for median line (default: 'red')
        median_linewidth: Line width for median (default: 2)
        show_mean: Whether to show mean marker (default: False)
        mean_marker: Marker style for mean (default: 'D' diamond)
        mean_color: Color for mean marker (default: 'green')
        mean_size: Size for mean marker (default: 8)
        
        # Whisker and cap appearance
        whisker_color: Color for whiskers (default: 'black')
        whisker_linewidth: Line width for whiskers (default: 1.5)
        whisker_linestyle: Line style for whiskers (default: '-')
        cap_color: Color for caps (default: 'black')
        cap_linewidth: Line width for caps (default: 1.5)
        
        # Outlier (flier) appearance
        show_fliers: Whether to show outliers (default: True)
        flier_marker: Marker style for outliers (default: 'o')
        flier_color: Face color for outliers (default: 'gray')
        flier_size: Size for outliers (default: 5)
        flier_alpha: Alpha for outliers (default: 0.6)
        flier_edgecolor: Edge color for outliers (default: 'none')
        
        # Scatter points (jittered data points)
        show_scatter: Whether to show individual data points (default: False)
        scatter_color: Color for scatter points (default: 'darkgray')
        scatter_alpha: Alpha for scatter points (default: 0.5)
        scatter_size: Size for scatter points (default: 20)
        scatter_jitter: Amount of horizontal jitter for scatter (default: 0.05)
        scatter_marker: Marker style for scatter (default: 'o')
        scatter_use_box_color: Whether to use box color for scatter points (default: False)
        
        # Background appearance
        bg_alpha: Alpha for background regions (default: 0.15)
        bg_extend: Extend background beyond box positions (default: 0.4)
        
        # Notch (confidence interval visualization)
        show_notch: Whether to show notch for confidence interval (default: False)
        
        # Violin overlay
        show_violin: Whether to overlay violin plot (default: False)
        violin_alpha: Alpha for violin plot (default: 0.2)
        violin_color: Color for violin (default: same as box or 'lightgray')
        violin_width: Width of violin (default: 0.8)
        
        # Statistical annotations
        show_stats: Whether to show stats annotations (default: False)
        stats_fontsize: Font size for stats text (default: 8)
        stats_format: Format string for stats (default: 'median: {median:.2f}\nmean: {mean:.2f}')
        
        # Labels and display
        xlabel: X-axis label (default: '')
        ylabel: Y-axis label (default: '')
        title: Plot title (default: '')
        label_size: Font size for axis labels (default: 12)
        title_size: Font size for title (default: 14)
        tick_size: Font size for tick labels (default: 10)
        tick_rotation: Rotation for x-axis tick labels (default: 0)
        hide_labels: Whether to hide axis labels (default: False)
        hide_spines: List of spines to hide (default: ['top', 'right'])
        
        # Grid
        show_grid: Whether to show grid (default: False)
        grid_alpha: Alpha for grid lines (default: 0.3)
        grid_axis: Axis for grid ('y', 'x', 'both') (default: 'y')
        
        # Axis limits
        ylim: Y-axis limits as ``(ymin, ymax)`` tuple (default: ``None`` → auto).
        
        # Other
        show: Whether to display the plot (default: True)
        ax: Existing axis to plot on (default: None, creates new figure)
        
    Returns:
        fig: matplotlib Figure object
        ax: matplotlib Axes object
        bp: Box plot artist (returned by ax.boxplot)
        
    Examples:
        # Basic usage with list of DataFrames
        box_plot([df1, df2, df3], 'metric_column', 
                 labels=['Scenario A', 'Scenario B', 'Scenario C'])
        
        # Single DataFrame with a categorical column
        box_plot(df, 'collaboration_rate', cat_col='penalty',
                 ylabel='Collaboration Rate', title='Rate by Penalty')
        
        # With custom box colors and background colors
        box_plot([df1, df2, df3, df4], 'value',
                 labels=['A', 'B', 'C', 'D'],
                 box_colors=['#8dadc3', '#ce5759', '#7fbf7b', '#af8dc3'],
                 bg_colors=['#f0f0f0', '#e8e8e8', '#f0f0f0', '#e8e8e8'])
        
        # With scatter points and mean markers
        box_plot(data_list, 'score', show_scatter=True, show_mean=True,
                 scatter_use_box_color=True, mean_color='darkgreen')
        
        # With violin overlay
        box_plot(data_list, 'distribution', show_violin=True, violin_alpha=0.3)
        
        # With statistical annotations
        box_plot(data_list, 'values', show_stats=True, 
                 stats_format='μ={mean:.1f}')
        
        # With all lines matching box color
        box_plot(data_list, 'metric', use_box_color_for_lines=True,
                 box_colors=['#8dadc3', '#ce5759', '#7fbf7b'])
    """
    import matplotlib.patches as patches
    
    # Extract kwargs with defaults
    # Box appearance
    box_alpha = kwargs.get('box_alpha', 0.7)
    box_linewidth = kwargs.get('box_linewidth', 1.5)
    box_edgecolor = kwargs.get('box_edgecolor', 'black')
    use_box_color_for_lines = kwargs.get('use_box_color_for_lines', False)
    
    # Median and mean
    show_median = kwargs.get('show_median', True)
    median_color = kwargs.get('median_color', 'red')
    median_linewidth = kwargs.get('median_linewidth', 2)
    show_mean = kwargs.get('show_mean', False)
    mean_marker = kwargs.get('mean_marker', 'D')
    mean_color = kwargs.get('mean_color', 'green')
    mean_size = kwargs.get('mean_size', 8)
    
    # Whisker and cap
    whisker_color = kwargs.get('whisker_color', 'black')
    whisker_linewidth = kwargs.get('whisker_linewidth', 1.5)
    whisker_linestyle = kwargs.get('whisker_linestyle', '-')
    cap_color = kwargs.get('cap_color', 'black')
    cap_linewidth = kwargs.get('cap_linewidth', 1.5)
    
    # Outliers (fliers)
    show_fliers = kwargs.get('show_fliers', True)
    flier_marker = kwargs.get('flier_marker', 'o')
    flier_color = kwargs.get('flier_color', 'gray')
    flier_size = kwargs.get('flier_size', 5)
    flier_alpha = kwargs.get('flier_alpha', 0.6)
    flier_edgecolor = kwargs.get('flier_edgecolor', 'none')
    
    # Scatter points
    show_scatter = kwargs.get('show_scatter', False)
    scatter_color = kwargs.get('scatter_color', 'darkgray')
    scatter_alpha = kwargs.get('scatter_alpha', 0.5)
    scatter_size = kwargs.get('scatter_size', 20)
    scatter_jitter = kwargs.get('scatter_jitter', 0.05)
    scatter_marker = kwargs.get('scatter_marker', 'o')
    scatter_use_box_color = kwargs.get('scatter_use_box_color', False)
    
    # Background
    bg_alpha = kwargs.get('bg_alpha', 0.15)
    bg_extend = kwargs.get('bg_extend', 0.4)
    
    # Notch
    show_notch = kwargs.get('show_notch', False)
    
    # Violin
    show_violin = kwargs.get('show_violin', False)
    violin_alpha = kwargs.get('violin_alpha', 0.2)
    violin_color = kwargs.get('violin_color', None)
    violin_width = kwargs.get('violin_width', 0.8)
    
    # Stats annotations
    show_stats = kwargs.get('show_stats', False)
    stats_fontsize = kwargs.get('stats_fontsize', 8)
    stats_format = kwargs.get('stats_format', 'median: {median:.2f}\nmean: {mean:.2f}')
    
    # Labels and display
    xlabel = kwargs.get('xlabel', '')
    ylabel = kwargs.get('ylabel', '')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    tick_size = kwargs.get('tick_size', 10)
    tick_rotation = kwargs.get('tick_rotation', 0)
    hide_labels = kwargs.get('hide_labels', False)
    hide_spines = kwargs.get('hide_spines', ['top', 'right'])
    
    # Grid
    show_grid = kwargs.get('show_grid', False)
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    grid_axis = kwargs.get('grid_axis', 'y')
    
    # Axis limits
    ylim = kwargs.get('ylim', None)
    
    # Other
    show = kwargs.get('show', True)
    ax = kwargs.get('ax', None)
    
    # Prepare data
    import pandas as pd
    
    # --- Normalise input: single DataFrame + cat_col → list of sub-DataFrames ---
    if isinstance(data_list, pd.DataFrame):
        if cat_col is None:
            raise ValueError(
                "When 'data_list' is a single DataFrame, 'cat_col' must be specified "
                "to indicate the categorical column used for grouping."
            )
        if cat_col not in data_list.columns:
            raise ValueError(f"Categorical column '{cat_col}' not found in DataFrame.")
        if col_name not in data_list.columns:
            raise ValueError(f"Value column '{col_name}' not found in DataFrame.")
        
        # Sort categories so the order is deterministic
        sorted_cats = sorted(data_list[cat_col].dropna().unique())
        grouped = data_list.groupby(cat_col)
        data_list_internal = [grouped.get_group(cat) for cat in sorted_cats]
        
        # Auto-generate labels from category values when not explicitly given
        if labels is None:
            labels = [str(cat) for cat in sorted_cats]
    else:
        data_list_internal = data_list
    
    plot_data = []
    for item in data_list_internal:
        if hasattr(item, 'columns'):  # DataFrame
            if col_name not in item.columns:
                raise ValueError(f"Column '{col_name}' not found in DataFrame.")
            data = item[col_name].dropna().values
        else:  # Series or array-like
            data = np.array(item)
            data = data[~np.isnan(data)]
        plot_data.append(data)
    
    n_boxes = len(plot_data)
    
    # Set default labels
    if labels is None:
        labels = [f'Box {i+1}' for i in range(n_boxes)]
    
    # Set default box colors
    if box_colors is None:
        box_colors = sns.color_palette('husl', n_boxes)
    
    # Create figure if not provided
    if ax is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        fig = ax.figure
    
    # Box positions (1-indexed for matplotlib boxplot)
    positions = list(range(1, n_boxes + 1))
    
    # Draw background regions FIRST (behind everything)
    if bg_colors is not None:
        y_min, y_max = ax.get_ylim()
        # Pre-calculate y limits from data
        all_data = np.concatenate(plot_data)
        data_min, data_max = np.min(all_data), np.max(all_data)
        data_range = data_max - data_min
        y_min = data_min - 0.1 * data_range
        y_max = data_max + 0.1 * data_range
        
        # Calculate equal-width non-overlapping background regions
        # Each box gets exactly the same background width
        bg_width = 1.0  # Each background region has equal width of 1.0 (same as box spacing)
        
        for i, (pos, bg_color) in enumerate(zip(positions, bg_colors)):
            if bg_color is not None:
                # Each box has equal background width centered on the box position
                left = pos - bg_width / 2
                right = pos + bg_width / 2
                
                rect = patches.Rectangle(
                    (left, y_min),
                    right - left,
                    y_max - y_min,
                    facecolor=bg_color,
                    alpha=bg_alpha,
                    edgecolor='none',
                    zorder=0
                )
                ax.add_patch(rect)
        
        # Set y limits to ensure background is visible
        ax.set_ylim(y_min, y_max)
    
    # Draw violin plot if requested (behind boxes)
    if show_violin:
        parts = ax.violinplot(plot_data, positions=positions, 
                              widths=violin_width, showmeans=False, 
                              showmedians=False, showextrema=False)
        for i, pc in enumerate(parts['bodies']):
            v_color = violin_color if violin_color else box_colors[i]
            pc.set_facecolor(v_color)
            pc.set_alpha(violin_alpha)
            pc.set_edgecolor('none')
    
    # Create box plot
    bp = ax.boxplot(
        plot_data,
        positions=positions,
        labels=labels,
        patch_artist=True,
        notch=show_notch,
        showfliers=show_fliers,
        showmeans=show_mean,
        meanprops=dict(
            marker=mean_marker,
            markerfacecolor=mean_color,
            markeredgecolor=mean_color,
            markersize=mean_size
        ),
        medianprops=dict(
            color=median_color if show_median else 'none',
            linewidth=median_linewidth
        ),
        whiskerprops=dict(
            color=whisker_color,
            linewidth=whisker_linewidth,
            linestyle=whisker_linestyle
        ),
        capprops=dict(
            color=cap_color,
            linewidth=cap_linewidth
        ),
        flierprops=dict(
            marker=flier_marker,
            markerfacecolor=flier_color,
            markersize=flier_size,
            alpha=flier_alpha,
            markeredgecolor=flier_edgecolor
        )
    )
    
    # Set box colors
    for i, (box, color) in enumerate(zip(bp['boxes'], box_colors)):
        box.set_facecolor(color)
        box.set_alpha(box_alpha)
        box.set_linewidth(box_linewidth)
        
        if use_box_color_for_lines:
            # Use darkened box color for box edges, whiskers, caps, and fliers
            # Note: median and mean keep their own specified colors
            line_color = darken_color(color, 0.7)
            box.set_edgecolor(line_color)
            
            # Each box has 2 whiskers (top and bottom)
            bp['whiskers'][i*2].set_color(line_color)
            bp['whiskers'][i*2 + 1].set_color(line_color)
            
            # Each box has 2 caps
            bp['caps'][i*2].set_color(line_color)
            bp['caps'][i*2 + 1].set_color(line_color)
            
            # Fliers (outliers) - use box color
            if show_fliers and i < len(bp['fliers']):
                bp['fliers'][i].set_markerfacecolor(color)
                bp['fliers'][i].set_markeredgecolor(line_color)
        else:
            box.set_edgecolor(box_edgecolor)
    
    # Add scatter points if requested
    if show_scatter:
        for i, (data, pos) in enumerate(zip(plot_data, positions)):
            # Add jitter
            jitter = np.random.uniform(-scatter_jitter, scatter_jitter, size=len(data))
            x_jittered = pos + jitter
            
            s_color = box_colors[i] if scatter_use_box_color else scatter_color
            ax.scatter(x_jittered, data, 
                      c=[s_color], 
                      alpha=scatter_alpha, 
                      s=scatter_size,
                      marker=scatter_marker,
                      edgecolors='white',
                      linewidth=0.5,
                      zorder=3)
    
    # Add stats annotations if requested
    if show_stats:
        for i, (data, pos) in enumerate(zip(plot_data, positions)):
            median_val = np.median(data)
            mean_val = np.mean(data)
            stats_text = stats_format.format(median=median_val, mean=mean_val, 
                                             std=np.std(data), n=len(data),
                                             min=np.min(data), max=np.max(data))
            ax.annotate(stats_text, xy=(pos, np.max(data)), 
                       xytext=(0, 5), textcoords='offset points',
                       ha='center', va='bottom', fontsize=stats_fontsize,
                       bbox=dict(boxstyle='round,pad=0.3', facecolor='white', alpha=0.7))
    
    # Hide spines
    for spine in hide_spines:
        ax.spines[spine].set_visible(False)
    
    # Set labels
    if not hide_labels:
        ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
        ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')
    
    # Set tick properties
    ax.tick_params(axis='x', labelsize=tick_size, rotation=tick_rotation)
    ax.tick_params(axis='y', labelsize=tick_size)
    
    # Grid
    if show_grid:
        ax.grid(True, axis=grid_axis, alpha=grid_alpha, linestyle='--')
    
    # Apply y-axis limits
    if ylim is not None:
        ax.set_ylim(ylim)
    
    plt.tight_layout()
    
    # Save figure if path provided
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight', pad_inches=0, transparent=True)
    
    if show:
        plt.show()
    
    return fig, ax, bp


def _ensure_cmap(cmap):
    """Convert *cmap* to a matplotlib Colormap if it is a list of colours."""
    if isinstance(cmap, (list, tuple)):
        from matplotlib.colors import LinearSegmentedColormap
        return LinearSegmentedColormap.from_list(
            'custom_cmap', [mcolors.to_rgb(c) for c in cmap], N=256
        )
    return cmap


def heatmap_plot(data_df,
                 x_col,
                 y_col,
                 value_col,
                 agg_method='mean',
                 pivot_df=None,
                 real_scale_x=True,
                 figure_size=(10, 6),
                 dpi=350,
                 figure_folder=None,
                 filename=None,
                 **kwargs):
    """
    Plot a heatmap from a DataFrame by grouping, aggregating, and unstacking.

    The function performs ``data_df.groupby([y_col, x_col])[value_col].agg(agg_method).unstack()``
    to produce a 2-D pivot table, then renders it as a heatmap.  Alternatively, a
    pre-computed pivot DataFrame can be passed directly via ``pivot_df``.

    Args:
        data_df: Source DataFrame (ignored when ``pivot_df`` is provided).
        x_col: Column name for the x-axis (columns of the pivot table).
        y_col: Column name for the y-axis (index/rows of the pivot table).
        value_col: Column name whose values are aggregated and displayed.
        agg_method: Aggregation method – any string accepted by ``DataFrame.agg()``
                    (e.g. ``'mean'``, ``'median'``, ``'sum'``, ``'std'``, ``'count'``),
                    or a callable.  Default: ``'mean'``.
        pivot_df: (Optional) Pre-computed pivot DataFrame.  When provided,
                  ``data_df``, ``x_col``, ``y_col``, ``value_col`` and ``agg_method``
                  are ignored for data preparation; the pivot is used as-is.
        real_scale_x: If ``True`` and the pivot-table column labels are
                      numeric, each column's display width is proportional
                      to the gap between adjacent x values, reflecting the
                      true x-axis scale instead of equal-width cells.  Falls
                      back to uniform spacing when labels are non-numeric.
                      Default: ``True``.
        figure_size: Figure size as ``(width, height)`` tuple (default: ``(10, 6)``).
        dpi: Figure resolution (default: 350).
        figure_folder: Folder path to save figure (optional).
        filename: Filename to save figure (optional).

    Keyword Args:
        # Colormap & color bar
        cmap: Matplotlib colormap name or instance (default: ``'YlOrRd'``).
        vmin: Minimum value for color scaling (default: auto).
        vmax: Maximum value for color scaling (default: auto).
        center: Value at which to center the colormap (default: ``None``).
        robust: If ``True``, use percentile-based vmin/vmax (default: ``False``).
        cbar: Whether to draw the colour bar (default: ``True``).
        cbar_label: Label for the colour bar (default: ``''``).
        cbar_label_size: Font size for colour-bar label (default: ``12``).
        cbar_tick_size: Font size for colour-bar tick labels (default: ``10``).
        cbar_orientation: ``'vertical'`` or ``'horizontal'`` (default: ``'vertical'``).
        cbar_shrink: Fraction by which to shrink the colour bar (default: ``1.0``).
        cbar_aspect: Aspect ratio of the colour bar (default: ``20``).

        # Cell annotation
        annot: Whether to annotate each cell with its value (default: ``True``).
        annot_fmt: Format string for annotations (default: ``'.2f'``).
        annot_size: Font size for annotations (default: ``10``).
        annot_color: Override annotation text colour (default: auto by seaborn).
        annot_fontweight: Font weight for annotations (default: ``'normal'``).

        # Line appearance
        linewidths: Width of lines between cells (default: ``0.5``).
        linecolor: Colour of lines between cells (default: ``'white'``).

        # Labels & title
        xlabel: X-axis label (default: ``x_col``).
        ylabel: Y-axis label (default: ``y_col``).
        title: Plot title (default: ``''``).
        label_size: Font size for axis labels (default: ``12``).
        title_size: Font size for title (default: ``14``).

        # Tick labels
        tick_size: Font size for tick labels (default: ``10``).
        x_tick_rotation: Rotation angle for x-tick labels (default: ``0``).
        y_tick_rotation: Rotation angle for y-tick labels (default: ``0``).
        x_tick_decimals: Number of decimal places for numeric x-tick labels
                         generated by ``real_scale_x``.  ``0`` for integers,
                         ``1`` for one decimal, etc.  ``None`` (default) uses
                         Python's default ``str()`` conversion.
        y_tick_decimals: Same as ``x_tick_decimals`` but for y-tick labels.
                         ``None`` (default) uses ``str()``.
        x_tick_labels: Custom x-tick labels list or ``False`` to hide (default: auto).
        y_tick_labels: Custom y-tick labels list or ``False`` to hide (default: auto).
        tick_label_map: Dict mapping original tick values to display strings.
                        Applied to **both** x and y ticks whose original values
                        appear as keys.

        # Spines & grid
        hide_spines: List of spines to hide (default: ``[]``).

        # Cell highlighting (cap_value)
        cap_value: Threshold value.  Cells whose value ``>=`` (or other
                   comparison via ``cap_compare``) this threshold are
                   highlighted with a prominent border (default: ``None``).
        cap_compare: Comparison operator string: ``'>='``, ``'>'``, ``'<='``,
                     ``'<'``, ``'=='``, ``'!='`` (default: ``'>='``).
        cap_edgecolor: Border colour for highlighted cells (default: ``'red'``).
        cap_linewidth: Border width for highlighted cells (default: ``2.5``).
        cap_linestyle: Border linestyle (default: ``'-'``).
        cap_fill: Whether to add a translucent fill to highlighted cells
                  (default: ``False``).
        cap_fill_color: Fill colour when ``cap_fill=True`` (default: same as
                        ``cap_edgecolor``).
        cap_fill_alpha: Fill alpha (default: ``0.12``).
        cap_outer_only: When ``True``, draw only the outer boundary of
                        contiguous highlighted regions instead of framing
                        every individual cell (default: ``False``).

        # Axis & display
        square: Whether to force square cells (default: ``False``).
        invert_yaxis: Whether to invert the y-axis (default: ``False``).
        ax: Existing Axes to plot on (default: ``None`` → new figure).
        show: Whether to call ``plt.show()`` (default: ``True``).

    Returns:
        fig: matplotlib Figure object.
        ax: matplotlib Axes object.
        pivot_table: The 2-D pivot DataFrame used for plotting.

    Examples:
        # Basic: mean collaboration rate by allocation_factor × penalty
        heatmap_plot(df, x_col='penalty', y_col='allocation_factor',
                     value_col='collaboration_rate')

        # Median with custom colour map and annotation format
        heatmap_plot(df, 'penalty', 'allocation_factor', 'VKT_km',
                     agg_method='median', cmap='coolwarm', annot_fmt='.1f')

        # Pre-computed pivot
        pivot = df.groupby(['af', 'penalty'])['rate'].mean().unstack()
        heatmap_plot(None, None, None, None, pivot_df=pivot, cmap='Blues')
    """
    import pandas as pd

    # --- Build pivot table ------------------------------------------------
    if pivot_df is not None:
        pivot_table = pivot_df.copy()
    else:
        if data_df is None:
            raise ValueError("Either 'data_df' or 'pivot_df' must be provided.")
        for col in (x_col, y_col, value_col):
            if col not in data_df.columns:
                raise ValueError(f"Column '{col}' not found in DataFrame.")
        pivot_table = (
            data_df
            .groupby([y_col, x_col])[value_col]
            .agg(agg_method)
            .unstack()
        )

    # --- Extract kwargs ---------------------------------------------------
    # Colormap & colour bar
    cmap = _ensure_cmap(kwargs.get('cmap', 'YlOrRd'))
    vmin = kwargs.get('vmin', None)
    vmax = kwargs.get('vmax', None)
    center = kwargs.get('center', None)
    robust = kwargs.get('robust', False)
    cbar = kwargs.get('cbar', True)
    cbar_label = kwargs.get('cbar_label', '')
    cbar_label_size = kwargs.get('cbar_label_size', 12)
    cbar_tick_size = kwargs.get('cbar_tick_size', 10)
    cbar_orientation = kwargs.get('cbar_orientation', 'vertical')
    cbar_shrink = kwargs.get('cbar_shrink', 1.0)
    cbar_aspect = kwargs.get('cbar_aspect', 20)

    # Cell annotation
    annot = kwargs.get('annot', True)
    annot_fmt = kwargs.get('annot_fmt', '.2f')
    annot_size = kwargs.get('annot_size', 10)
    annot_color = kwargs.get('annot_color', None)
    annot_fontweight = kwargs.get('annot_fontweight', 'normal')

    # Lines
    linewidths = kwargs.get('linewidths', 0.5)
    linecolor = kwargs.get('linecolor', 'white')

    # Labels & title
    xlabel = kwargs.get('xlabel', x_col if x_col else '')
    ylabel = kwargs.get('ylabel', y_col if y_col else '')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)

    # Ticks
    tick_size = kwargs.get('tick_size', 10)
    x_tick_rotation = kwargs.get('x_tick_rotation', 0)
    y_tick_rotation = kwargs.get('y_tick_rotation', 0)
    x_tick_decimals = kwargs.get('x_tick_decimals', None)
    y_tick_decimals = kwargs.get('y_tick_decimals', None)
    x_tick_labels = kwargs.get('x_tick_labels', None)
    y_tick_labels = kwargs.get('y_tick_labels', None)
    tick_label_map = kwargs.get('tick_label_map', None)

    # Spines
    hide_spines = kwargs.get('hide_spines', [])

    # Cell highlighting (cap_value)
    cap_value = kwargs.get('cap_value', None)
    cap_compare = kwargs.get('cap_compare', '>=')
    cap_edgecolor = kwargs.get('cap_edgecolor', 'red')
    cap_linewidth = kwargs.get('cap_linewidth', 2.5)
    cap_linestyle = kwargs.get('cap_linestyle', '-')
    cap_fill = kwargs.get('cap_fill', False)
    cap_fill_color = kwargs.get('cap_fill_color', None)
    cap_fill_alpha = kwargs.get('cap_fill_alpha', 0.12)
    cap_outer_only = kwargs.get('cap_outer_only', False)

    # Misc
    square = kwargs.get('square', False)
    invert_yaxis = kwargs.get('invert_yaxis', False)
    ax = kwargs.get('ax', None)
    show = kwargs.get('show', True)

    # --- real_scale_x: compute cell geometry ------------------------------
    nrows, ncols = pivot_table.shape
    _use_real_scale = False
    if real_scale_x:
        try:
            _x_vals = np.array([float(c) for c in pivot_table.columns])
            if len(_x_vals) >= 2:
                _x_edges = np.empty(len(_x_vals) + 1)
                for k in range(1, len(_x_vals)):
                    _x_edges[k] = (_x_vals[k - 1] + _x_vals[k]) / 2.0
                _x_edges[0] = _x_vals[0] - (_x_edges[1] - _x_vals[0])
                _x_edges[-1] = _x_vals[-1] + (_x_vals[-1] - _x_edges[-2])
                _use_real_scale = True
            else:
                _use_real_scale = False
        except (ValueError, TypeError):
            import warnings
            warnings.warn(
                "real_scale_x=True but column labels are not numeric; "
                "falling back to uniform spacing."
            )
            _use_real_scale = False

    if _use_real_scale:
        _x_lefts = _x_edges[:-1]
        _x_widths = np.diff(_x_edges)
        _x_centers = (_x_edges[:-1] + _x_edges[1:]) / 2.0
    else:
        _x_edges = np.arange(ncols + 1, dtype=float)
        _x_lefts = np.arange(ncols, dtype=float)
        _x_widths = np.ones(ncols, dtype=float)
        _x_centers = np.arange(ncols, dtype=float) + 0.5

    # --- Create figure ----------------------------------------------------
    if ax is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        fig = ax.figure

    # --- Build annotation kwargs ------------------------------------------
    annot_kws = {'size': annot_size, 'fontweight': annot_fontweight}
    if annot_color is not None:
        annot_kws['color'] = annot_color

    # --- Colour-bar kwargs ------------------------------------------------
    cbar_kws = {
        'label': cbar_label,
        'orientation': cbar_orientation,
        'shrink': cbar_shrink,
        'aspect': cbar_aspect,
    }

    # --- Draw heatmap -----------------------------------------------------
    if _use_real_scale:
        # --- real-scale path: pcolormesh for variable-width columns --------
        _y_edges = np.arange(nrows + 1, dtype=float)
        _Z = np.ma.masked_invalid(pivot_table.values.astype(float))

        # Build Normalize
        _eff_vmin = vmin
        _eff_vmax = vmax
        if robust and _eff_vmin is None:
            _eff_vmin = float(np.nanpercentile(pivot_table.values.astype(float), 2))
        if robust and _eff_vmax is None:
            _eff_vmax = float(np.nanpercentile(pivot_table.values.astype(float), 98))
        if center is not None:
            _norm = TwoSlopeNorm(vcenter=center, vmin=_eff_vmin, vmax=_eff_vmax)
        else:
            _norm = plt.Normalize(vmin=_eff_vmin, vmax=_eff_vmax)

        mesh = ax.pcolormesh(
            _x_edges, _y_edges, _Z,
            cmap=cmap, norm=_norm,
            edgecolors=linecolor, linewidth=linewidths,
            shading='flat',
        )

        # Colour bar
        if cbar:
            _rs_cb = fig.colorbar(mesh, ax=ax, **cbar_kws)
            _rs_cb.ax.tick_params(labelsize=cbar_tick_size)
            if cbar_label:
                _rs_cb.set_label(cbar_label, fontsize=cbar_label_size,
                                 fontweight='bold')

        # Set x ticks at cell centres (visual midpoints of each column)
        ax.set_xticks(_x_centers)
        if x_tick_decimals is not None:
            ax.set_xticklabels([f'{v:.{int(x_tick_decimals)}f}' for v in _x_vals])
        else:
            ax.set_xticklabels([str(v) for v in _x_vals])
        # Set y ticks at row centres
        ax.set_yticks(np.arange(nrows) + 0.5)
        if y_tick_decimals is not None:
            ax.set_yticklabels([f'{float(idx):.{int(y_tick_decimals)}f}'
                                for idx in pivot_table.index])
        else:
            ax.set_yticklabels([str(idx) for idx in pivot_table.index])

    else:
        # --- uniform-spacing path: seaborn heatmap ------------------------
        # Pass annot=False; we draw annotations manually below for reliability.
        sns.heatmap(
            pivot_table,
            ax=ax,
            cmap=cmap,
            vmin=vmin,
            vmax=vmax,
            center=center,
            robust=robust,
            annot=False,
            linewidths=linewidths,
            linecolor=linecolor,
            square=square,
            cbar=cbar,
            cbar_kws=cbar_kws,
        )

    # --- Manual cell annotations (avoids seaborn rendering bugs) ----------
    if annot:
        import matplotlib.colors as mcolors
        # Determine effective vmin / vmax from the drawn QuadMesh
        mesh = ax.collections[0]
        norm = mesh.norm
        colormap = mesh.cmap
        for i in range(nrows):
            for j in range(ncols):
                val = pivot_table.iloc[i, j]
                if pd.notna(val):
                    txt = format(val, annot_fmt)
                    # Pick text colour: white on dark cells, black on light
                    if annot_color is not None:
                        txt_color = annot_color
                    else:
                        rgba = colormap(norm(val))
                        lum = 0.2126 * rgba[0] + 0.7152 * rgba[1] + 0.0722 * rgba[2]
                        txt_color = 'white' if lum < 0.5 else 'black'
                    ax.text(
                        _x_centers[j], i + 0.5, txt,
                        ha='center', va='center',
                        fontsize=annot_size,
                        fontweight=annot_fontweight,
                        color=txt_color,
                    )

    # --- Highlight cells meeting cap_value condition ---------------------
    if cap_value is not None:
        import operator
        _ops = {
            '>=': operator.ge, '>': operator.gt,
            '<=': operator.le, '<': operator.lt,
            '==': operator.eq, '!=': operator.ne,
        }
        cmp_fn = _ops.get(cap_compare)
        if cmp_fn is None:
            raise ValueError(
                f"Unknown cap_compare '{cap_compare}'. "
                f"Use one of {list(_ops.keys())}.")
        fill_c = cap_fill_color if cap_fill_color is not None else cap_edgecolor
        from matplotlib.patches import Rectangle

        if cap_outer_only:
            # Build boolean mask of highlighted cells
            mask = np.zeros((nrows, ncols), dtype=bool)
            for i in range(nrows):
                for j in range(ncols):
                    val = pivot_table.iloc[i, j]
                    if pd.notna(val) and cmp_fn(val, cap_value):
                        mask[i, j] = True
            # Optional translucent fill (no edge)
            if cap_fill:
                for i in range(nrows):
                    for j in range(ncols):
                        if mask[i, j]:
                            ax.add_patch(Rectangle(
                                (_x_lefts[j], i), _x_widths[j], 1,
                                linewidth=0,
                                edgecolor='none', facecolor=fill_c,
                                alpha=cap_fill_alpha, zorder=5))
            # Collect outer boundary edge segments (column-index based),
            # then draw using actual x-edge coordinates.
            h_edges = {}
            v_edges = {}
            for i in range(nrows):
                for j in range(ncols):
                    if not mask[i, j]:
                        continue
                    if i == 0 or not mask[i - 1, j]:
                        h_edges.setdefault(i, []).append(j)
                    if i == nrows - 1 or not mask[i + 1, j]:
                        h_edges.setdefault(i + 1, []).append(j)
                    if j == 0 or not mask[i, j - 1]:
                        v_edges.setdefault(j, []).append(i)
                    if j == ncols - 1 or not mask[i, j + 1]:
                        v_edges.setdefault(j + 1, []).append(i)

            def _merge_and_draw(edge_dict, horizontal):
                """Merge consecutive unit segments and draw."""
                for coord, starts in edge_dict.items():
                    starts.sort()
                    seg_s = starts[0]
                    seg_e = starts[0] + 1
                    for s in starts[1:]:
                        if s == seg_e:
                            seg_e = s + 1
                        else:
                            _draw_seg(coord, seg_s, seg_e, horizontal)
                            seg_s = s
                            seg_e = s + 1
                    _draw_seg(coord, seg_s, seg_e, horizontal)

            def _draw_seg(coord, s, e, horizontal):
                if horizontal:
                    ax.plot([_x_edges[s], _x_edges[e]],
                            [coord, coord],
                            color=cap_edgecolor, lw=cap_linewidth,
                            linestyle=cap_linestyle, zorder=6,
                            solid_capstyle='projecting',
                            clip_on=False)
                else:
                    ax.plot([_x_edges[coord], _x_edges[coord]],
                            [s, e],
                            color=cap_edgecolor, lw=cap_linewidth,
                            linestyle=cap_linestyle, zorder=6,
                            solid_capstyle='projecting',
                            clip_on=False)

            _merge_and_draw(h_edges, horizontal=True)
            _merge_and_draw(v_edges, horizontal=False)
        else:
            for i in range(nrows):
                for j in range(ncols):
                    val = pivot_table.iloc[i, j]
                    if pd.notna(val) and cmp_fn(val, cap_value):
                        rect = Rectangle(
                            (_x_lefts[j], i), _x_widths[j], 1,
                            linewidth=cap_linewidth,
                            edgecolor=cap_edgecolor,
                            linestyle=cap_linestyle,
                            facecolor=fill_c if cap_fill else 'none',
                            alpha=cap_fill_alpha if cap_fill else 1.0,
                            zorder=5,
                        )
                        ax.add_patch(rect)

    # --- Colour-bar tick font size (seaborn path only) --------------------
    if not _use_real_scale and cbar and ax.collections:
        cb = ax.collections[0].colorbar
        if cb is not None:
            cb.ax.tick_params(labelsize=cbar_tick_size)
            if cbar_label:
                cb.set_label(cbar_label, fontsize=cbar_label_size, fontweight='bold')

    # --- Tick labels ------------------------------------------------------
    # x ticks
    if x_tick_labels is not None:
        if x_tick_labels is False:
            ax.set_xticklabels([])
        else:
            ax.set_xticklabels(x_tick_labels)
    elif tick_label_map is not None:
        current = [t.get_text() for t in ax.get_xticklabels()]
        mapped = [str(tick_label_map.get(_try_numeric(lbl), lbl)) for lbl in current]
        ax.set_xticklabels(mapped)

    # y ticks
    if y_tick_labels is not None:
        if y_tick_labels is False:
            ax.set_yticklabels([])
        else:
            ax.set_yticklabels(y_tick_labels)
    elif tick_label_map is not None:
        current = [t.get_text() for t in ax.get_yticklabels()]
        mapped = [str(tick_label_map.get(_try_numeric(lbl), lbl)) for lbl in current]
        ax.set_yticklabels(mapped)

    ax.tick_params(axis='x', labelsize=tick_size, rotation=x_tick_rotation)
    ax.tick_params(axis='y', labelsize=tick_size, rotation=y_tick_rotation)

    # --- Labels & title ---------------------------------------------------
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight='bold')
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight='bold')
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')

    # --- Spines -----------------------------------------------------------
    for spine in hide_spines:
        ax.spines[spine].set_visible(False)

    # --- Invert y-axis ----------------------------------------------------
    if invert_yaxis:
        ax.invert_yaxis()

    plt.tight_layout()

    # --- Save -------------------------------------------------------------
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight', pad_inches=0, transparent=True)

    if show:
        plt.show()

    return fig, ax, pivot_table


def gradient_plot(data_df,
                  x_col,
                  y_col,
                  value_col,
                  agg_method='mean',
                  pivot_df=None,
                  figure_size=(10, 6),
                  dpi=350,
                  figure_folder=None,
                  filename=None,
                  **kwargs):
    """
    Draw a contour / iso-line plot from the same data accepted by
    :func:`heatmap_plot`.

    Instead of rendering a rectangular grid of coloured cells, this function
    treats the (x, y) pairs as coordinates on a 2-D surface and draws
    filled contours and/or contour lines to visualise the underlying scalar
    field – much like a topographic map.

    Args:
        data_df: Source DataFrame (ignored when ``pivot_df`` is provided).
        x_col: Column name for the x-axis.
        y_col: Column name for the y-axis.
        value_col: Column whose aggregated values define the z-surface.
        agg_method: Aggregation method (default ``'mean'``).
        pivot_df: Pre-computed pivot DataFrame (rows = y, columns = x).
        figure_size: ``(width, height)`` tuple (default ``(10, 6)``).
        dpi: Figure resolution (default 350).
        figure_folder: Folder path to save figure (optional).
        filename: Filename to save figure (optional).

    Keyword Args:
        # Levels / binning
        levels: Number of contour levels **or** an explicit sequence of
                level boundaries.  Default: ``10``.
        step: If given (e.g. ``0.1``), overrides ``levels`` by building
              ``np.arange(vmin, vmax + step, step)``.
        value_decimals: Round aggregated Z values to this many decimal
                        places **before** computing contour levels and
                        interpolating.  For example, ``1`` rounds to one
                        decimal (``0.3``, ``0.7``, …).  ``None`` (default)
                        keeps the original precision.

        # Interpolation
        interp_method: Interpolation method.  Default: ``'rbf'``.

                       * ``'rbf'`` – Radial Basis Function (exact
                         interpolator): the surface passes through every
                         data point exactly while remaining smooth.
                         Uses ``scipy.interpolate.RBFInterpolator``.
                       * ``'cubic'``, ``'linear'``, ``'nearest'`` – passed
                         to ``scipy.interpolate.griddata``.  ``'cubic'``
                         is smooth but **not** exact at data points.

        rbf_kernel: RBF kernel when ``interp_method='rbf'``.
                    Default: ``'thin_plate_spline'``.
        rbf_smoothing: Smoothing parameter for RBF.  ``0`` (default) =
                       exact interpolation.  Increase slightly (e.g.
                       ``0.01``) to trade fidelity for extra smoothness.
        interp_resolution: Number of grid points along each axis for the
                           interpolated surface.  Default: ``200``.
        interpolate: Whether to interpolate the data onto a fine grid
                     before contouring.  If ``False``, the raw (x, y, Z)
                     grid from the pivot table is used directly – contour
                     lines will only pass through actual data points.
                     Default: ``True``.
        mask_beyond_data: If ``True`` (default ``True``), mask the
                          interpolated surface at grid points whose
                          nearest real data point is farther than
                          ``mask_radius``.  This prevents contour lines
                          from appearing in regions with no observations.
        mask_radius: Maximum distance (in data-coordinate units) from a
                     real data point before the interpolated cell is
                     masked.  Default: ``None`` → auto-computed as
                     1.2 × the maximum spacing among adjacent x or y
                     values, which works well for rectangular grids.

        # Colourmap & value range
        cmap: Matplotlib colormap (default: ``'RdYlBu_r'``).
        vmin: Minimum value for colour scaling (default: data min).
        vmax: Maximum value for colour scaling (default: data max).

        # Fill / line appearance
        filled: Whether to draw filled contours (default: ``True``).
        filled_alpha: Alpha of the filled contours (default: ``0.85``).
        show_lines: Whether to draw contour lines (default: ``True``).
        line_widths: Contour-line width (default: ``0.8``).
        line_colors: Override line colours (default: ``'k'``).
        line_alpha: Alpha for contour lines (default: ``0.6``).
        line_styles: Contour-line style, e.g. ``'-'``, ``'--'``,
                     ``'-.'``, ``':'`` (default: ``'-'``).
        show_clabels: Whether to label contour lines (default: ``True``).
        clabel_fmt: Format string for contour labels (default: ``'%.2f'``).
        clabel_fontsize: Font size for contour labels (default: ``8``).
        clabel_fontweight: Font weight for contour labels (default: ``'bold'``).
        clabel_inline: Whether labels are inline (default: ``True``).
        clabel_color: Colour for contour labels (default: ``None`` → use
                      contour-line colour).
        clabel_alpha: Alpha for contour labels (default: ``None`` → fully
                      opaque).
        clabel_positions: Where to place contour labels.  Default: ``'auto'``.

                          * ``'auto'``  – matplotlib chooses positions
                            automatically.
                          * ``'end'``   – place one label per level
                            **outside** the plot area on the side given
                            by ``clabel_end_side``.  The label is
                            positioned at the x (or y) coordinate where
                            that contour level is closest to the chosen
                            border, and offset outward by
                            ``clabel_end_offset``.
                          * A list of ``(x, y)`` tuples – passed directly
                            to ``ax.clabel(manual=...)``.

        clabel_rotation: Rotation angle (degrees) for contour labels.
                         ``None`` (default) lets matplotlib rotate each
                         label to follow its contour line.  Set to ``0``
                         for horizontal labels, or any other angle.
        clabel_rightside_up: If ``True`` (default), matplotlib ensures
                             labels are never upside-down.
        clabel_end_side: Which border to attach ``'end'`` labels to.
                         One of ``'top'`` (default), ``'bottom'``,
                         ``'left'``, ``'right'``.
        clabel_end_offset: Distance (in data-coordinate units) to push
                           ``'end'`` labels beyond the border.  Default:
                           ``None`` → auto (2 % of the axis span).

        # Colour bar
        cbar: Whether to show the colour bar (default: ``True``).
        cbar_label: Colour-bar label (default: ``''``).
        cbar_label_size: Font size for colour-bar label (default: ``12``).
        cbar_tick_size: Font size for colour-bar tick labels (default: ``10``).
        cbar_orientation: ``'vertical'`` or ``'horizontal'``
                          (default: ``'vertical'``).
        cbar_shrink: Fraction by which to shrink colour bar (default ``1.0``).
        cbar_aspect: Colour-bar aspect ratio (default ``20``).

        # Labels & title
        xlabel: X-axis label (default: ``x_col``).
        ylabel: Y-axis label (default: ``y_col``).
        title: Plot title (default: ``''``).
        label_size: Font size for axis labels (default: ``12``).
        title_size: Font size for title (default: ``14``).
        label_fontweight: Font weight for labels (default: ``'bold'``).

        # Ticks
        tick_size: Font size for tick labels (default: ``10``).
        x_tick_rotation: Rotation of x-tick labels (default: ``0``).
        y_tick_rotation: Rotation of y-tick labels (default: ``0``).
        x_tick_decimals: Number of decimal places for x-tick labels.
                         ``0`` for integers, ``1`` for one decimal, etc.
                         ``None`` (default) uses Python's ``str()``.
        y_tick_decimals: Same as ``x_tick_decimals`` but for y-tick labels.
        x_ticks: Explicit x-tick positions (default: auto from data).
        y_ticks: Explicit y-tick positions (default: auto from data).

        # Scatter overlay
        show_points: Overlay the original (x, y) data points as scatter
                     markers (default: ``False``).
        point_size: Marker size (default: ``15``).
        point_color: Marker colour (default: ``'black'``).
        point_marker: Marker style (default: ``'o'``).
        point_alpha: Marker alpha (default: ``0.7``).

        # Cap / highlight
        cap_value: Threshold value (default: ``None``).
        cap_compare: Comparison operator string (default: ``'>='``).
        cap_line_color: Colour of the cap boundary contour
                        (default: ``'red'``).
        cap_line_width: Width of the cap boundary contour
                        (default: ``2.5``).
        cap_line_style: Linestyle for cap boundary (default: ``'-'``).
        cap_fill: Whether to fill the highlighted region
                  (default: ``False``).
        cap_fill_color: Fill colour (default: same as ``cap_line_color``).
        cap_fill_alpha: Fill alpha (default: ``0.18``).
        cap_hatch: Hatch pattern for highlighted region, e.g. ``'//'``
                   (default: ``None``).
        cap_label: Legend label for the highlighted region
                   (default: ``None``).

        # Axis limits
        xlim: Explicit x-axis limits as ``(xmin, xmax)``
              (default: ``None`` → auto).
        ylim: Explicit y-axis limits as ``(ymin, ymax)``
              (default: ``None`` → auto).

        # Grid lines
        show_grid: Whether to show grid lines (default: ``False``).
        grid_color: Grid-line colour (default: ``'grey'``).
        grid_linestyle: Grid-line style (default: ``'--'``).
        grid_linewidth: Grid-line width (default: ``0.5``).
        grid_alpha: Grid-line alpha (default: ``0.5``).
        grid_which: Which ticks to draw grid on – ``'major'``,
                    ``'minor'``, or ``'both'`` (default: ``'major'``).
        grid_axis: Axis for grid – ``'both'``, ``'x'``, or ``'y'``
                   (default: ``'both'``).

        # Legend & display
        show_legend: Whether to show a legend (default: ``False``).
        legend_loc: Legend location (default: ``'best'``).
        legend_fontsize: Legend font size (default: ``10``).
        hide_spines: List of spines to hide (default: ``[]``).
        invert_yaxis: Whether to invert y-axis (default: ``False``).
        ax: Existing Axes to plot on (default: ``None``).
        show: Whether to call ``plt.show()`` (default: ``True``).

    Returns:
        fig: matplotlib Figure.
        ax: matplotlib Axes.
        pivot_table: The 2-D pivot DataFrame used for plotting.

    Examples:
        # Basic contour from raw data
        gradient_plot(df, 'penalty', 'allocation_factor',
                      'collaboration_rate', step=0.1)

        # Pre-computed pivot, highlight region >= 0.8
        gradient_plot(None, None, None, None, pivot_df=pivot,
                      cap_value=0.8, cap_fill=True, cap_fill_color='red')
    """
    import pandas as pd
    from scipy.interpolate import griddata

    # --- Build pivot table ------------------------------------------------
    if pivot_df is not None:
        pivot_table = pivot_df.copy()
    else:
        if data_df is None:
            raise ValueError("Either 'data_df' or 'pivot_df' must be provided.")
        for col in (x_col, y_col, value_col):
            if col not in data_df.columns:
                raise ValueError(f"Column '{col}' not found in DataFrame.")
        pivot_table = (
            data_df
            .groupby([y_col, x_col])[value_col]
            .agg(agg_method)
            .unstack()
        )

    # --- Convert axes to numeric ------------------------------------------
    try:
        x_vals = np.array([float(c) for c in pivot_table.columns])
        y_vals = np.array([float(r) for r in pivot_table.index])
    except (ValueError, TypeError) as exc:
        raise ValueError(
            "gradient_plot requires numeric x and y labels to build a "
            "contour surface."
        ) from exc

    Z_raw = pivot_table.values.astype(float)
    nrows, ncols = Z_raw.shape

    # --- Extract kwargs ---------------------------------------------------
    # Levels
    levels = kwargs.get('levels', 10)
    step = kwargs.get('step', None)
    value_decimals = kwargs.get('value_decimals', None)

    # Interpolation
    interp_method = kwargs.get('interp_method', 'rbf')
    interp_resolution = kwargs.get('interp_resolution', 200)
    interpolate = kwargs.get('interpolate', True)
    rbf_kernel = kwargs.get('rbf_kernel', 'thin_plate_spline')
    rbf_smoothing = kwargs.get('rbf_smoothing', 0)
    mask_beyond_data = kwargs.get('mask_beyond_data', True)
    mask_radius = kwargs.get('mask_radius', None)

    # Colourmap
    cmap = _ensure_cmap(kwargs.get('cmap', 'RdYlBu_r'))
    vmin = kwargs.get('vmin', None)
    vmax = kwargs.get('vmax', None)

    # Fill / lines
    filled = kwargs.get('filled', True)
    filled_alpha = kwargs.get('filled_alpha', 0.85)
    show_lines = kwargs.get('show_lines', True)
    line_widths = kwargs.get('line_widths', 0.8)
    line_colors = kwargs.get('line_colors', 'k')
    line_alpha = kwargs.get('line_alpha', 0.6)
    line_styles = kwargs.get('line_styles', '-')
    show_clabels = kwargs.get('show_clabels', True)
    clabel_fmt = kwargs.get('clabel_fmt', '%.2f')
    clabel_fontsize = kwargs.get('clabel_fontsize', 8)
    clabel_fontweight = kwargs.get('clabel_fontweight', 'bold')
    clabel_inline = kwargs.get('clabel_inline', True)
    clabel_color = kwargs.get('clabel_color', None)
    clabel_alpha = kwargs.get('clabel_alpha', None)
    clabel_positions = kwargs.get('clabel_positions', 'auto')
    clabel_rotation = kwargs.get('clabel_rotation', None)
    clabel_rightside_up = kwargs.get('clabel_rightside_up', True)
    clabel_end_side = kwargs.get('clabel_end_side', 'top')
    clabel_end_offset = kwargs.get('clabel_end_offset', None)

    # Colour bar
    cbar = kwargs.get('cbar', True)
    cbar_label = kwargs.get('cbar_label', '')
    cbar_label_size = kwargs.get('cbar_label_size', 12)
    cbar_tick_size = kwargs.get('cbar_tick_size', 10)
    cbar_orientation = kwargs.get('cbar_orientation', 'vertical')
    cbar_shrink = kwargs.get('cbar_shrink', 1.0)
    cbar_aspect = kwargs.get('cbar_aspect', 20)

    # Labels & title
    xlabel = kwargs.get('xlabel', x_col if x_col else '')
    ylabel = kwargs.get('ylabel', y_col if y_col else '')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    label_fontweight = kwargs.get('label_fontweight', 'bold')

    # Ticks
    tick_size = kwargs.get('tick_size', 10)
    x_tick_rotation = kwargs.get('x_tick_rotation', 0)
    y_tick_rotation = kwargs.get('y_tick_rotation', 0)
    x_tick_decimals = kwargs.get('x_tick_decimals', None)
    y_tick_decimals = kwargs.get('y_tick_decimals', None)
    x_ticks = kwargs.get('x_ticks', None)
    y_ticks = kwargs.get('y_ticks', None)

    # Scatter overlay
    show_points = kwargs.get('show_points', False)
    point_size = kwargs.get('point_size', 15)
    point_color = kwargs.get('point_color', 'black')
    point_marker = kwargs.get('point_marker', 'o')
    point_alpha = kwargs.get('point_alpha', 0.7)

    # Cap / highlight
    cap_value = kwargs.get('cap_value', None)
    cap_compare = kwargs.get('cap_compare', '>=')
    cap_line_color = kwargs.get('cap_line_color', 'red')
    cap_line_width = kwargs.get('cap_line_width', 2.5)
    cap_line_style = kwargs.get('cap_line_style', '-')
    cap_fill = kwargs.get('cap_fill', False)
    cap_fill_color = kwargs.get('cap_fill_color', None)
    cap_fill_alpha = kwargs.get('cap_fill_alpha', 0.18)
    cap_hatch = kwargs.get('cap_hatch', None)
    cap_label = kwargs.get('cap_label', None)

    # Legend & display
    # Axis limits
    xlim = kwargs.get('xlim', None)
    ylim = kwargs.get('ylim', None)

    # Grid lines
    show_grid = kwargs.get('show_grid', False)
    grid_color = kwargs.get('grid_color', 'grey')
    grid_linestyle = kwargs.get('grid_linestyle', '--')
    grid_linewidth = kwargs.get('grid_linewidth', 0.5)
    grid_alpha = kwargs.get('grid_alpha', 0.5)
    grid_which = kwargs.get('grid_which', 'major')
    grid_axis = kwargs.get('grid_axis', 'both')

    show_legend = kwargs.get('show_legend', False)
    legend_loc = kwargs.get('legend_loc', 'best')
    legend_fontsize = kwargs.get('legend_fontsize', 10)
    hide_spines = kwargs.get('hide_spines', [])
    invert_yaxis = kwargs.get('invert_yaxis', False)
    ax_in = kwargs.get('ax', None)
    show = kwargs.get('show', True)

    # --- Effective vmin / vmax --------------------------------------------
    # Round Z values if requested
    if value_decimals is not None:
        Z_raw = np.round(Z_raw, int(value_decimals))
    _valid = Z_raw[~np.isnan(Z_raw)]
    _eff_vmin = vmin if vmin is not None else float(np.min(_valid))
    _eff_vmax = vmax if vmax is not None else float(np.max(_valid))

    # --- Build level array ------------------------------------------------
    if step is not None:
        _levels = np.arange(_eff_vmin, _eff_vmax + step * 0.5, step)
    elif isinstance(levels, (int, np.integer)):
        _levels = np.linspace(_eff_vmin, _eff_vmax, int(levels) + 1)
    else:
        _levels = np.asarray(levels, dtype=float)

    # --- Interpolation to a fine grid -------------------------------------
    # Build observed (x, y, z) triples – skip NaN entries
    X_grid, Y_grid = np.meshgrid(x_vals, y_vals)
    mask_valid = ~np.isnan(Z_raw)
    pts = np.column_stack([X_grid[mask_valid], Y_grid[mask_valid]])
    vals = Z_raw[mask_valid]

    if interpolate:
        # Build fine grid that *includes* the original data coordinates
        # so that contour lines pass exactly through data points.
        xi = np.union1d(
            x_vals,
            np.linspace(x_vals.min(), x_vals.max(), interp_resolution),
        )
        yi = np.union1d(
            y_vals,
            np.linspace(y_vals.min(), y_vals.max(), interp_resolution),
        )
        Xi, Yi = np.meshgrid(xi, yi)

        if interp_method == 'rbf':
            # RBF interpolation – exact at data points, smooth everywhere
            from scipy.interpolate import RBFInterpolator
            # Normalise coordinates so kernel is isotropic
            _x_span = x_vals.max() - x_vals.min() if x_vals.max() != x_vals.min() else 1.0
            _y_span = y_vals.max() - y_vals.min() if y_vals.max() != y_vals.min() else 1.0
            _pts_n = np.column_stack([pts[:, 0] / _x_span, pts[:, 1] / _y_span])
            _rbf = RBFInterpolator(
                _pts_n, vals,
                kernel=rbf_kernel,
                smoothing=rbf_smoothing,
            )
            _qi_n = np.column_stack([Xi.ravel() / _x_span, Yi.ravel() / _y_span])
            Zi = _rbf(_qi_n).reshape(Xi.shape)
        else:
            Zi = griddata(pts, vals, (Xi, Yi), method=interp_method)
            # Fill any remaining NaN from cubic with nearest
            if np.any(np.isnan(Zi)):
                Zi_nearest = griddata(pts, vals, (Xi, Yi), method='nearest')
                _nan_mask = np.isnan(Zi)
                Zi[_nan_mask] = Zi_nearest[_nan_mask]

        # Round interpolated surface to match value_decimals
        if value_decimals is not None:
            Zi = np.round(Zi, int(value_decimals))

        # --- Mask regions far from real data ------------------------------
        if mask_beyond_data:
            from scipy.spatial import cKDTree
            _x_span = x_vals.max() - x_vals.min() if x_vals.max() != x_vals.min() else 1.0
            _y_span = y_vals.max() - y_vals.min() if y_vals.max() != y_vals.min() else 1.0
            _pts_norm = np.column_stack([pts[:, 0] / _x_span, pts[:, 1] / _y_span])
            _qi_norm = np.column_stack([Xi.ravel() / _x_span, Yi.ravel() / _y_span])
            _tree = cKDTree(_pts_norm)
            _dists, _ = _tree.query(_qi_norm)
            if mask_radius is not None:
                _r_norm = mask_radius / max(_x_span, _y_span)
            else:
                _dx = np.diff(np.sort(np.unique(pts[:, 0]))) / _x_span if len(np.unique(pts[:, 0])) > 1 else np.array([0.5])
                _dy = np.diff(np.sort(np.unique(pts[:, 1]))) / _y_span if len(np.unique(pts[:, 1])) > 1 else np.array([0.5])
                _r_norm = 1.2 * max(_dx.max(), _dy.max())
            _far_mask = _dists.reshape(Zi.shape) > _r_norm
            Zi = np.ma.array(Zi, mask=_far_mask)
    else:
        # No interpolation – use the raw pivot grid directly
        Xi, Yi = X_grid.astype(float), Y_grid.astype(float)
        Zi = np.ma.masked_invalid(Z_raw.astype(float))

    # Round levels to value_decimals to avoid floating-point drift
    if value_decimals is not None:
        _levels = np.round(_levels, int(value_decimals))
        # Remove duplicates that may arise from rounding
        _levels = np.unique(_levels)

    # --- Create figure ----------------------------------------------------
    if ax_in is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        ax = ax_in
        fig = ax.figure

    # --- Filled contours --------------------------------------------------
    cf = None
    if filled:
        cf = ax.contourf(
            Xi, Yi, Zi,
            levels=_levels,
            cmap=cmap,
            vmin=_eff_vmin,
            vmax=_eff_vmax,
            alpha=filled_alpha,
            extend='both',
        )

    # --- Contour lines ----------------------------------------------------
    cs = None
    if show_lines:
        cs = ax.contour(
            Xi, Yi, Zi,
            levels=_levels,
            colors=line_colors,
            linewidths=line_widths,
            linestyles=line_styles,
            alpha=line_alpha,
        )
        if show_clabels and cs is not None:
            # --- Determine label placement mode ---------------------------
            _use_end_text = False          # flag: use ax.text instead of ax.clabel
            _manual = False

            if isinstance(clabel_positions, str):
                if clabel_positions == 'end':
                    _use_end_text = True
                # else 'auto' → _manual stays False
            elif clabel_positions is not None:
                # User-supplied list of (x, y)
                _manual = list(clabel_positions)

            if _use_end_text:
                # ----- 'end' mode: text annotations outside the plot ------
                # Collect all contour segments per level.
                # Segments must be 2-D arrays of shape (N, 2).
                def _ensure_2d(arr):
                    """Return *arr* as a (N, 2) float array, or None."""
                    a = np.asarray(arr, dtype=float)
                    if a.ndim == 2 and a.shape[1] >= 2:
                        return a[:, :2]
                    if a.ndim == 1 and a.size >= 2:
                        # Single point stored flat → reshape
                        return a.reshape(-1, 2) if a.size % 2 == 0 else None
                    return None

                _segs_by_level = {}  # {level_value: [seg_array, ...]}

                # Method 1 – matplotlib >= 3.8: iterate over paths
                # stored directly on the ContourSet
                if hasattr(cs, 'get_paths'):
                    try:
                        _paths = cs.get_paths()
                    except Exception:
                        _paths = []
                    if _paths:
                        # Map each path to its level via the internal
                        # _indices array or by matching levels.
                        _lvl_for_path = []
                        if hasattr(cs, '_indices') and cs._indices is not None:
                            # _indices[i]:_indices[i+1] gives path range
                            for i, lvl in enumerate(cs.levels):
                                lo = cs._indices[i]
                                hi = cs._indices[i + 1] if i + 1 < len(cs._indices) else len(_paths)
                                for j in range(int(lo), int(hi)):
                                    _lvl_for_path.append(lvl)
                        else:
                            # Fallback: equal spread
                            _lvl_for_path = list(cs.levels) if len(_paths) == len(cs.levels) else []

                        for j, p in enumerate(_paths):
                            verts = _ensure_2d(p.vertices)
                            if verts is not None and len(verts):
                                lvl = _lvl_for_path[j] if j < len(_lvl_for_path) else None
                                if lvl is not None:
                                    _segs_by_level.setdefault(lvl, []).append(verts)

                # Method 2 – older matplotlib with allsegs
                if not _segs_by_level and hasattr(cs, 'allsegs'):
                    for lvl, seg_list in zip(cs.levels, cs.allsegs):
                        for s in seg_list:
                            s2 = _ensure_2d(s)
                            if s2 is not None and len(s2):
                                _segs_by_level.setdefault(lvl, []).append(s2)

                # Method 3 – older matplotlib with collections
                if not _segs_by_level and hasattr(cs, 'collections'):
                    for lvl, coll in zip(cs.levels, cs.collections):
                        for path in coll.get_paths():
                            verts = _ensure_2d(path.vertices)
                            if verts is not None and len(verts):
                                _segs_by_level.setdefault(lvl, []).append(verts)

                # Determine the border side and offsets
                _side = clabel_end_side.lower()
                _y_span = float(y_vals.max() - y_vals.min()) if len(y_vals) > 1 else 1.0
                _x_span = float(x_vals.max() - x_vals.min()) if len(x_vals) > 1 else 1.0

                if _side in ('top', 'bottom'):
                    _off = clabel_end_offset if clabel_end_offset is not None else _y_span * 0.02
                else:
                    _off = clabel_end_offset if clabel_end_offset is not None else _x_span * 0.02

                _ha_map = {'top': 'center', 'bottom': 'center',
                           'left': 'right', 'right': 'left'}
                _va_map = {'top': 'bottom', 'bottom': 'top',
                           'left': 'center', 'right': 'center'}
                _rot = clabel_rotation if clabel_rotation is not None else 0

                for lvl, segs in _segs_by_level.items():
                    # Choose the vertex closest to the target border
                    best_pt = None
                    best_dist = np.inf
                    for seg in segs:
                        if _side == 'top':
                            idx = np.argmax(seg[:, 1])
                        elif _side == 'bottom':
                            idx = np.argmin(seg[:, 1])
                        elif _side == 'right':
                            idx = np.argmax(seg[:, 0])
                        else:  # left
                            idx = np.argmin(seg[:, 0])
                        candidate = seg[idx]
                        if _side == 'top':
                            d = float(y_vals.max()) - candidate[1]
                        elif _side == 'bottom':
                            d = candidate[1] - float(y_vals.min())
                        elif _side == 'right':
                            d = float(x_vals.max()) - candidate[0]
                        else:
                            d = candidate[0] - float(x_vals.min())
                        if d < best_dist:
                            best_dist = d
                            best_pt = candidate

                    if best_pt is None:
                        continue

                    # Compute label position outside the border
                    if _side == 'top':
                        lx, ly = best_pt[0], float(y_vals.max()) + _off
                    elif _side == 'bottom':
                        lx, ly = best_pt[0], float(y_vals.min()) - _off
                    elif _side == 'right':
                        lx, ly = float(x_vals.max()) + _off, best_pt[1]
                    else:
                        lx, ly = float(x_vals.min()) - _off, best_pt[1]

                    # Format level value
                    _label_str = clabel_fmt % lvl
                    _c = clabel_color if clabel_color is not None else line_colors
                    _a = clabel_alpha if clabel_alpha is not None else 1.0

                    ax.text(
                        lx, ly, _label_str,
                        fontsize=clabel_fontsize,
                        fontweight=clabel_fontweight,
                        color=_c,
                        alpha=_a,
                        ha=_ha_map[_side],
                        va=_va_map[_side],
                        rotation=_rot,
                        clip_on=False,
                        zorder=10,
                    )

                # Expand axis limits so labels are visible
                _cur_ylim = ax.get_ylim()
                _cur_xlim = ax.get_xlim()
                if _side == 'top':
                    ax.set_ylim(_cur_ylim[0], float(y_vals.max()) + _off * 3)
                elif _side == 'bottom':
                    ax.set_ylim(float(y_vals.min()) - _off * 3, _cur_ylim[1])
                elif _side == 'right':
                    ax.set_xlim(_cur_xlim[0], float(x_vals.max()) + _off * 3)
                elif _side == 'left':
                    ax.set_xlim(float(x_vals.min()) - _off * 3, _cur_xlim[1])

            else:
                # ----- 'auto' or manual (x,y) mode -----------------------
                clbl = ax.clabel(
                    cs,
                    inline=clabel_inline,
                    fontsize=clabel_fontsize,
                    fmt=clabel_fmt,
                    manual=_manual,
                    rightside_up=clabel_rightside_up,
                )
                if clbl:
                    for txt in clbl:
                        txt.set_fontweight(clabel_fontweight)
                        if clabel_color is not None:
                            txt.set_color(clabel_color)
                        if clabel_alpha is not None:
                            txt.set_alpha(clabel_alpha)
                        if clabel_rotation is not None:
                            txt.set_rotation(clabel_rotation)

    # --- Colour bar -------------------------------------------------------
    if cbar:
        _mappable = cf if cf is not None else cs
        if _mappable is not None:
            _cbar_kws = dict(
                orientation=cbar_orientation,
                shrink=cbar_shrink,
                aspect=cbar_aspect,
            )
            _cb = fig.colorbar(_mappable, ax=ax, **_cbar_kws)
            _cb.ax.tick_params(labelsize=cbar_tick_size)
            if cbar_label:
                _cb.set_label(
                    cbar_label,
                    fontsize=cbar_label_size,
                    fontweight=label_fontweight,
                )

    # --- Scatter overlay --------------------------------------------------
    if show_points:
        ax.scatter(
            X_grid[mask_valid], Y_grid[mask_valid],
            s=point_size, c=point_color,
            marker=point_marker, alpha=point_alpha,
            zorder=5, linewidths=0.3, edgecolors='white',
        )

    # --- Cap / highlight --------------------------------------------------
    if cap_value is not None:
        import operator
        _ops = {
            '>=': operator.ge, '>': operator.gt,
            '<=': operator.le, '<': operator.lt,
            '==': operator.eq, '!=': operator.ne,
        }
        cmp_fn = _ops.get(cap_compare)
        if cmp_fn is None:
            raise ValueError(
                f"Unknown cap_compare '{cap_compare}'. "
                f"Use one of {list(_ops.keys())}."
            )

        # Determine cap contour levels for filling
        _cap_fill_c = cap_fill_color if cap_fill_color is not None else cap_line_color
        if cap_compare in ('>=', '>'):
            _cap_levels = [cap_value, _eff_vmax + (_eff_vmax - _eff_vmin) * 0.1]
        elif cap_compare in ('<=', '<'):
            _cap_levels = [_eff_vmin - (_eff_vmax - _eff_vmin) * 0.1, cap_value]
        else:
            # For == / !=, just draw the line; fill is ambiguous
            _cap_levels = [cap_value - 1e-9, cap_value + 1e-9]

        # Cap boundary line
        _cap_cs = ax.contour(
            Xi, Yi, Zi,
            levels=[cap_value],
            colors=[cap_line_color],
            linewidths=[cap_line_width],
            linestyles=[cap_line_style],
            zorder=6,
        )

        # Cap fill
        if cap_fill:
            _hatches = [cap_hatch] if cap_hatch else [None]
            _cap_cf = ax.contourf(
                Xi, Yi, Zi,
                levels=_cap_levels,
                colors=[_cap_fill_c],
                alpha=cap_fill_alpha,
                hatches=_hatches,
                zorder=4,
            )
            # Optional legend entry
            if cap_label is not None:
                from matplotlib.patches import Patch
                _patch = Patch(
                    facecolor=_cap_fill_c,
                    alpha=cap_fill_alpha,
                    hatch=cap_hatch,
                    edgecolor=cap_line_color,
                    label=cap_label,
                )
                ax.add_patch(_patch)
                _patch.set_visible(False)  # only show in legend

    # --- Ticks ------------------------------------------------------------
    _xt = x_ticks if x_ticks is not None else x_vals
    _yt = y_ticks if y_ticks is not None else y_vals
    ax.set_xticks(_xt)
    ax.set_yticks(_yt)
    if x_tick_decimals is not None:
        ax.set_xticklabels([f'{v:.{int(x_tick_decimals)}f}' for v in _xt])
    if y_tick_decimals is not None:
        ax.set_yticklabels([f'{v:.{int(y_tick_decimals)}f}' for v in _yt])

    ax.tick_params(axis='x', labelsize=tick_size, rotation=x_tick_rotation)
    ax.tick_params(axis='y', labelsize=tick_size, rotation=y_tick_rotation)

    # --- Labels & title ---------------------------------------------------
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight=label_fontweight)
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight=label_fontweight)
    if title:
        ax.set_title(title, fontsize=title_size, fontweight=label_fontweight)

    # --- Spines -----------------------------------------------------------
    for spine in hide_spines:
        ax.spines[spine].set_visible(False)

    # --- Axis limits ------------------------------------------------------
    if xlim is not None:
        ax.set_xlim(xlim)
    if ylim is not None:
        ax.set_ylim(ylim)

    # --- Grid lines -------------------------------------------------------
    if show_grid:
        ax.grid(
            True,
            which=grid_which,
            axis=grid_axis,
            color=grid_color,
            linestyle=grid_linestyle,
            linewidth=grid_linewidth,
            alpha=grid_alpha,
        )
        ax.set_axisbelow(True)

    # --- Invert y-axis ----------------------------------------------------
    if invert_yaxis:
        ax.invert_yaxis()

    # --- Legend ------------------------------------------------------------
    if show_legend:
        ax.legend(loc=legend_loc, fontsize=legend_fontsize)

    plt.tight_layout()

    # --- Save -------------------------------------------------------------
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight', pad_inches=0,
                    transparent=True)

    if show:
        plt.show()

    return fig, ax, pivot_table


def plot_change_rate(
        pivot_df=None,
        data_df=None,
        group_col=None,
        x_col=None,
        value_col=None,
        agg_method='mean',
        figure_size=(8, 5),
        dpi=350,
        figure_folder=None,
        filename=None,
        **kwargs):
    """
    Plot multi-line chart where each row of a pivot table is a separate line,
    with the pivot columns as the x-axis.

    Supports two input modes:

    1.  **Pre-computed pivot** – pass ``pivot_df`` directly (e.g. ``change_value_df``
        or ``collab_value_df``).  Each *row* becomes a line; the index values
        are used as legend labels and the column values are the x-axis.

    2.  **Raw DataFrame** – pass ``data_df`` together with ``group_col``,
        ``x_col``, ``value_col`` and optionally ``agg_method``.  The function
        internally performs::

            data_df.groupby([group_col, x_col])[value_col].agg(agg_method).unstack()

        which produces the same kind of pivot table.

    Args:
        pivot_df: Pre-computed pivot DataFrame (rows = lines, columns = x-axis).
        data_df: Raw DataFrame to aggregate (ignored when ``pivot_df`` is given).
        group_col: Column name used to define each line (groupby key 1).
        x_col: Column name for the x-axis (groupby key 2 / pivot columns).
        value_col: Column name whose aggregated values are plotted.
        agg_method: Aggregation method (default ``'mean'``).
        figure_size: ``(width, height)`` in inches (default ``(8, 5)``).
        dpi: Figure resolution (default ``350``).
        figure_folder: Folder path for saving the figure (optional).
        filename: Filename for saving the figure (optional).

    Keyword Args:
        # --- Colour & line style ---
        colors: List of colours for the lines.  If ``None``, defaults to
                a Matplotlib colour-cycle palette (tab10).
        linewidth / lw: Line width (default ``1.8``).
        linestyle / ls: A single linestyle or a list of linestyles per line
                        (default ``'-'``).
        alpha: Line alpha (default ``1.0``).

        # --- Markers ---
        marker: Marker style or list of marker styles (default ``'o'``).
        markersize / ms: Marker size (default ``5``).
        markeredgecolor: Marker edge colour (default ``None`` → auto).
        markerfacecolor: Marker face colour (default ``None`` → line colour).

        # --- Fill between ---
        fill_between: Whether to shade the area under each line (default
                      ``False``).
        fill_alpha: Alpha for the shaded area (default ``0.15``).

        # --- Labels & title ---
        xlabel: X-axis label (default ``''``).
        ylabel: Y-axis label (default ``''``).
        title: Figure title (default ``''``).
        label_size: Font size for axis labels (default ``12``).
        title_size: Font size for title (default ``14``).
        label_fontweight: Font weight for labels (default ``'bold'``).

        # --- Legend ---
        show_legend: Whether to show the legend (default ``True``).
        legend_title: Title text for the legend (default ``None``).
        legend_loc: Legend location (default ``'best'``).
        legend_fontsize: Legend item font size (default ``10``).
        legend_title_fontsize: Legend title font size (default ``11``).
        legend_ncol: Number of legend columns (default ``1``).
        legend_frameon: Draw legend frame (default ``True``).
        legend_bbox_to_anchor: Bbox anchor for legend placement (default ``None``).
        legend_labels: Custom labels list for legend items, in the same
                       order as the pivot rows (default ``None`` → index values).

        # --- Tick labels ---
        tick_size: Font size for tick labels (default ``10``).
        x_tick_rotation: X-tick-label rotation (default ``0``).
        y_tick_rotation: Y-tick-label rotation (default ``0``).
        x_tick_labels: Custom x-tick labels list (default ``None`` → column values).
        tick_label_map: Dict mapping original tick values to display strings.

        # --- Grid ---
        show_grid: Whether to show grid lines (default ``True``).
        grid_axis: ``'both'``, ``'x'``, or ``'y'`` (default ``'y'``).
        grid_alpha: Grid line alpha (default ``0.3``).
        grid_linestyle: Grid linestyle (default ``'--'``).

        # --- Spines ---
        hide_spines: List of spines to remove, e.g. ``['top', 'right']``
                     (default ``['top', 'right']``).

        # --- Reference lines ---
        hlines: List of y-values at which to draw horizontal reference lines
                (default ``[]``).
        hline_colors: Colour(s) for ``hlines`` (default ``'grey'``).
        hline_linestyles: Linestyle(s) for ``hlines`` (default ``'--'``).
        hline_linewidths: Line-width(s) for ``hlines`` (default ``0.8``).

        # --- Axes limits & scale ---
        xlim: ``(xmin, xmax)`` or ``None`` (default ``None``).
        ylim: ``(ymin, ymax)`` or ``None`` (default ``None``).

        # --- Misc ---
        ax: Existing Axes to draw on (default ``None`` → new figure).
        show: Whether to call ``plt.show()`` (default ``True``).
        tight_layout: Whether to apply tight layout (default ``True``).

    Returns:
        fig: matplotlib Figure.
        ax: matplotlib Axes.
        pivot_table: The 2-D pivot DataFrame used for plotting.

    Examples:
        # Mode 1 – pre-computed pivot
        plot_change_rate(pivot_df=change_value_df,
                         ylabel='Collaboration-rate Drop',
                         xlabel='Penalty (€ / h)')

        # Mode 2 – raw DataFrame
        plot_change_rate(data_df=all_metrics_random_df,
                         group_col='allocation_factor',
                         x_col='penalty_std',
                         value_col='collaboration_rate',
                         agg_method='mean',
                         ylabel='Mean Collaboration Rate')
    """
    import pandas as pd

    # ── Build pivot table ─────────────────────────────────────────────────
    if pivot_df is not None:
        pivot_table = pivot_df.copy()
    elif data_df is not None:
        if group_col is None or x_col is None or value_col is None:
            raise ValueError(
                "When using 'data_df', 'group_col', 'x_col' and 'value_col' "
                "must all be provided."
            )
        for col in (group_col, x_col, value_col):
            if col not in data_df.columns:
                raise ValueError(f"Column '{col}' not found in DataFrame.")
        pivot_table = (
            data_df
            .groupby([group_col, x_col])[value_col]
            .agg(agg_method)
            .unstack()
        )
    else:
        raise ValueError("Either 'pivot_df' or 'data_df' must be provided.")

    # ── Extract kwargs ────────────────────────────────────────────────────
    # Colours & line style
    colors = kwargs.get('colors', None)
    linewidth = kwargs.get('linewidth', kwargs.get('lw', 1.8))
    linestyle = kwargs.get('linestyle', kwargs.get('ls', '-'))
    alpha = kwargs.get('alpha', 1.0)

    # Markers
    marker = kwargs.get('marker', 'o')
    markersize = kwargs.get('markersize', kwargs.get('ms', 5))
    markeredgecolor = kwargs.get('markeredgecolor', None)
    markerfacecolor = kwargs.get('markerfacecolor', None)

    # Fill
    fill_between = kwargs.get('fill_between', False)
    fill_alpha = kwargs.get('fill_alpha', 0.15)

    # Labels & title
    xlabel = kwargs.get('xlabel', '')
    ylabel = kwargs.get('ylabel', '')
    title = kwargs.get('title', '')
    label_size = kwargs.get('label_size', 12)
    title_size = kwargs.get('title_size', 14)
    label_fontweight = kwargs.get('label_fontweight', 'bold')

    # Legend
    show_legend = kwargs.get('show_legend', True)
    legend_title = kwargs.get('legend_title', None)
    legend_loc = kwargs.get('legend_loc', 'best')
    legend_fontsize = kwargs.get('legend_fontsize', 10)
    legend_title_fontsize = kwargs.get('legend_title_fontsize', 11)
    legend_ncol = kwargs.get('legend_ncol', 1)
    legend_frameon = kwargs.get('legend_frameon', True)
    legend_bbox_to_anchor = kwargs.get('legend_bbox_to_anchor', None)
    legend_labels = kwargs.get('legend_labels', None)

    # Ticks
    tick_size = kwargs.get('tick_size', 10)
    x_tick_rotation = kwargs.get('x_tick_rotation', 0)
    y_tick_rotation = kwargs.get('y_tick_rotation', 0)
    x_tick_labels = kwargs.get('x_tick_labels', None)
    tick_label_map = kwargs.get('tick_label_map', None)

    # Grid
    show_grid = kwargs.get('show_grid', True)
    grid_axis = kwargs.get('grid_axis', 'y')
    grid_alpha = kwargs.get('grid_alpha', 0.3)
    grid_linestyle = kwargs.get('grid_linestyle', '--')

    # Spines
    hide_spines = kwargs.get('hide_spines', ['top', 'right'])

    # Reference lines
    hlines = kwargs.get('hlines', [])
    hline_colors = kwargs.get('hline_colors', 'grey')
    hline_linestyles = kwargs.get('hline_linestyles', '--')
    hline_linewidths = kwargs.get('hline_linewidths', 0.8)

    # Axes limits
    xlim = kwargs.get('xlim', None)
    ylim = kwargs.get('ylim', None)

    # Misc
    ax = kwargs.get('ax', None)
    show = kwargs.get('show', True)
    tight_layout = kwargs.get('tight_layout', True)

    # ── Create figure ─────────────────────────────────────────────────────
    if ax is None:
        fig, ax = plt.subplots(figsize=figure_size, dpi=dpi)
    else:
        fig = ax.figure

    n_lines = len(pivot_table)
    x_values = pivot_table.columns.values

    # Resolve colours
    if colors is None:
        cmap_tab = plt.get_cmap('tab10')
        colors = [cmap_tab(i % 10) for i in range(n_lines)]
    elif len(colors) < n_lines:
        colors = list(colors) + [plt.get_cmap('tab10')(i % 10)
                                  for i in range(len(colors), n_lines)]

    # Resolve per-line linestyle / marker lists
    if isinstance(linestyle, str):
        linestyle = [linestyle] * n_lines
    if isinstance(marker, str) or marker is None:
        marker = [marker] * n_lines

    # ── Plot lines ────────────────────────────────────────────────────────
    for idx, (row_label, row_data) in enumerate(pivot_table.iterrows()):
        label = (legend_labels[idx]
                 if legend_labels is not None and idx < len(legend_labels)
                 else str(row_label))
        c = colors[idx]
        ls = linestyle[idx] if idx < len(linestyle) else '-'
        mk = marker[idx] if idx < len(marker) else 'o'

        ax.plot(
            x_values, row_data.values,
            color=c,
            linewidth=linewidth,
            linestyle=ls,
            alpha=alpha,
            marker=mk,
            markersize=markersize,
            markeredgecolor=markeredgecolor if markeredgecolor else c,
            markerfacecolor=markerfacecolor if markerfacecolor else c,
            label=label,
        )

        if fill_between:
            ax.fill_between(x_values, row_data.values, alpha=fill_alpha, color=c)

    # ── Horizontal reference lines ────────────────────────────────────────
    if hlines:
        if isinstance(hline_colors, str):
            hline_colors = [hline_colors] * len(hlines)
        if isinstance(hline_linestyles, str):
            hline_linestyles = [hline_linestyles] * len(hlines)
        if isinstance(hline_linewidths, (int, float)):
            hline_linewidths = [hline_linewidths] * len(hlines)
        for yval, hc, hls, hlw in zip(hlines, hline_colors,
                                       hline_linestyles, hline_linewidths):
            ax.axhline(y=yval, color=hc, linestyle=hls, linewidth=hlw)

    # ── X-tick labels ─────────────────────────────────────────────────────
    ax.set_xticks(x_values)
    if x_tick_labels is not None:
        ax.set_xticklabels(x_tick_labels)
    elif tick_label_map is not None:
        ax.set_xticklabels([str(tick_label_map.get(_try_numeric(v), v))
                            for v in x_values])
    else:
        ax.set_xticklabels([str(v) for v in x_values])

    ax.tick_params(axis='x', labelsize=tick_size, rotation=x_tick_rotation)
    ax.tick_params(axis='y', labelsize=tick_size, rotation=y_tick_rotation)

    # ── Labels & title ────────────────────────────────────────────────────
    ax.set_xlabel(xlabel, fontsize=label_size, fontweight=label_fontweight)
    ax.set_ylabel(ylabel, fontsize=label_size, fontweight=label_fontweight)
    if title:
        ax.set_title(title, fontsize=title_size, fontweight='bold')

    # ── Legend ────────────────────────────────────────────────────────────
    if show_legend:
        legend_kw = dict(
            loc=legend_loc,
            fontsize=legend_fontsize,
            frameon=legend_frameon,
            ncol=legend_ncol,
        )
        if legend_title is not None:
            legend_kw['title'] = legend_title
            legend_kw['title_fontsize'] = legend_title_fontsize
        if legend_bbox_to_anchor is not None:
            legend_kw['bbox_to_anchor'] = legend_bbox_to_anchor
        ax.legend(**legend_kw)

    # ── Grid ──────────────────────────────────────────────────────────────
    if show_grid:
        ax.grid(True, axis=grid_axis, alpha=grid_alpha, linestyle=grid_linestyle)

    # ── Spines ────────────────────────────────────────────────────────────
    for spine in hide_spines:
        ax.spines[spine].set_visible(False)

    # ── Axes limits ───────────────────────────────────────────────────────
    if xlim is not None:
        ax.set_xlim(xlim)
    if ylim is not None:
        ax.set_ylim(ylim)

    # ── Layout & save ─────────────────────────────────────────────────────
    if tight_layout:
        plt.tight_layout()

    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight',
                    pad_inches=0, transparent=True)

    if show:
        plt.show()

    return fig, ax, pivot_table


def quadrant_donut_chart(
    data_dict: dict,
    value_col: str,
    figsize: tuple = (8, 8),
    dpi: int = 350,
    figure_folder: str = None,
    filename: str = None,
    show: bool = True,
    **kwargs
):
    """
    Create a donut chart divided into 4 equal quadrants (90° each), with
    coordinate-axes arrows separating the quadrants and customisable labels
    at the four arrow tips.

    Each quadrant corresponds to one item in *data_dict*. Within each 90°
    sector the space is subdivided proportionally by the value_counts of
    *value_col* in the associated DataFrame.

    The data_dict must contain **exactly 4** items.  The quadrant layout
    (counter-clockwise from the top-right, standard math convention with
    ``startangle=90``) is::

        Q1 (top-right)      ← 1st dict item
        Q2 (top-left)       ← 2nd dict item
        Q3 (bottom-left)    ← 3rd dict item
        Q4 (bottom-right)   ← 4th dict item

    Args:
        data_dict : dict
            ``{scenario_label: DataFrame}`` – exactly 4 entries.
        value_col : str
            Column whose ``value_counts()`` determines sub-sector sizes
            inside each quadrant.
        figsize : tuple
            Figure size ``(width, height)``.
        dpi : int
            Figure resolution.
        figure_folder : str | Path, optional
            Directory to save the figure.
        filename : str, optional
            Filename (with extension) for saving.
        show : bool
            Whether to call ``plt.show()``.

    Keyword Args:
        # ── Hole (centre) ──────────────────────────────────────────────
        hole_radius : float
            Radius of the empty centre hole (0–1 scale, default 0.35).
        hole_color : str
            Fill colour of the hole (default ``'white'``).
        hole_edgecolor : str
            Edge colour of the hole circle (default ``'white'``).
        hole_linewidth : float
            Edge line width of the hole circle (default 0).

        # ── Outer radius ──────────────────────────────────────────────
        outer_radius : float
            Outer radius of the donut (default 1.0).

        # ── Explode ───────────────────────────────────────────────────
        explode : float | list[float]
            Distance each *quadrant group* is pulled away from centre.
            A single float applies to all 4 quadrants; a list of 4 floats
            sets each one individually (default 0).

        # ── Colours ───────────────────────────────────────────────────
        quadrant_colors : list[list[str]] | None
            Nested list of colours for each quadrant's sub-sectors.
            ``quadrant_colors[i]`` is a list of colours for quadrant *i*.
            If None, auto-generated from *cmap*.
        cmap : str
            Colormap name used when ``quadrant_colors`` is None
            (default ``'tab10'``).
        edgecolor : str
            Edge colour between sub-sectors (default ``'white'``).
        edge_linewidth : float
            Edge line width between sub-sectors (default 1.5).

        # ── Percentage labels on sectors ──────────────────────────────
        show_pct : bool
            Show percentage text on sub-sectors (default True).
        pct_threshold : float
            Minimum percentage to display a label (default 5).
        pct_fontsize : int
            Font size for percentage labels (default 9).
        pct_color : str
            Font colour for percentage labels (default ``'white'``).

        # ── Quadrant title labels ─────────────────────────────────────
        show_quadrant_labels : bool
            Show scenario names near each quadrant (default True).
        quadrant_label_fontsize : int
            Font size for quadrant labels (default 11).
        quadrant_label_offset : float
            Radial offset factor from outer_radius (default 1.15).
        quadrant_label_fontweight : str
            Font weight for quadrant labels (default ``'bold'``).

        # ── Axes / arrows ─────────────────────────────────────────────
        show_axes : bool
            Draw the X and Y coordinate arrows (default True).
        axis_color : str
            Colour of the axis arrows (default ``'black'``).
        axis_linewidth : float
            Line width of axis arrows (default 1.5).
        axis_arrow_style : str
            ``FancyArrowPatch`` arrowstyle (default ``'->'``).
        axis_arrow_mutation : int
            Arrow mutation scale (default 15).
        axis_extend : float
            How far beyond *outer_radius* the arrows extend
            (default 0.15).

        # ── Axis labels (arrow-tip labels) ────────────────────────────
        xlabel_right : str
            Label at the right arrow tip (default ``''``).
        xlabel_left : str
            Label at the left arrow tip (default ``''``).
        ylabel_top : str
            Label at the top arrow tip (default ``''``).
        ylabel_bottom : str
            Label at the bottom arrow tip (default ``''``).
        axis_label_fontsize : int
            Font size for axis labels (default 12).
        axis_label_fontweight : str
            Font weight for axis labels (default ``'bold'``).
        axis_label_offset : float
            Extra offset from the arrow tip for labels (default 0.06).

        # ── Legend ────────────────────────────────────────────────────
        show_legend : bool
            Show a legend for the sub-sectors (default True).
        legend_title : str
            Legend title (default *value_col*).
        legend_loc : str
            Legend location (default ``'upper right'``).
        legend_bbox : tuple | None
            ``bbox_to_anchor`` for legend placement (default None).
        legend_fontsize : int
            Font size for legend entries (default 10).
        legend_ncol : int
            Number of legend columns (default 1).

        # ── Title ─────────────────────────────────────────────────────
        title : str
            Chart title (default ``''``).
        title_size : int
            Title font size (default 14).

        # ── Misc ──────────────────────────────────────────────────────
        startangle : float
            Start angle of the pie in degrees (default 90, i.e. 12-o'clock).
        transparent_bg : bool
            Set figure background to transparent (default False).

    Returns:
        (fig, ax) : tuple of matplotlib Figure and Axes objects.

    Examples:
        >>> data_dict = {
        ...     'Center-Clustered': df1,
        ...     'Center-Dispersed': df2,
        ...     'Outside-Clustered': df3,
        ...     'Outside-Dispersed': df4
        ... }
        >>> fig, ax = quadrant_donut_chart(
        ...     data_dict, 'final_fleet_size',
        ...     hole_radius=0.4,
        ...     xlabel_right='Dispersed →',
        ...     xlabel_left='← Clustered',
        ...     ylabel_top='Center ↑',
        ...     ylabel_bottom='↓ Outside',
        ... )
    """
    import matplotlib.patches as mpatches
    from matplotlib.patches import FancyArrowPatch
    import pandas as pd

    # ── Validate ──────────────────────────────────────────────────────────
    if len(data_dict) != 4:
        raise ValueError(
            f"data_dict must contain exactly 4 items, got {len(data_dict)}."
        )

    # ── Extract kwargs ────────────────────────────────────────────────────
    # Hole
    hole_radius      = kwargs.get('hole_radius', 0.35)
    hole_color       = kwargs.get('hole_color', 'white')
    hole_edgecolor   = kwargs.get('hole_edgecolor', 'white')
    hole_linewidth   = kwargs.get('hole_linewidth', 0)
    outer_radius     = kwargs.get('outer_radius', 1.0)

    # Explode
    _explode_raw = kwargs.get('explode', 0)
    if isinstance(_explode_raw, (int, float)):
        explode_per_quad = [float(_explode_raw)] * 4
    else:
        explode_per_quad = list(_explode_raw)
        if len(explode_per_quad) != 4:
            raise ValueError("explode list must have length 4.")

    # Colours
    quadrant_colors  = kwargs.get('quadrant_colors', None)
    cmap_name        = kwargs.get('cmap', 'tab10')
    edgecolor        = kwargs.get('edgecolor', 'white')
    edge_linewidth   = kwargs.get('edge_linewidth', 1.5)

    # Percentage labels
    show_pct         = kwargs.get('show_pct', True)
    pct_threshold    = kwargs.get('pct_threshold', 5)
    pct_fontsize     = kwargs.get('pct_fontsize', 9)
    pct_color        = kwargs.get('pct_color', 'white')

    # Quadrant labels
    show_quadrant_labels     = kwargs.get('show_quadrant_labels', True)
    quadrant_label_fontsize  = kwargs.get('quadrant_label_fontsize', 11)
    quadrant_label_offset    = kwargs.get('quadrant_label_offset', 1.15)
    quadrant_label_fontweight = kwargs.get('quadrant_label_fontweight', 'bold')

    # Axes / arrows
    show_axes          = kwargs.get('show_axes', True)
    axis_color         = kwargs.get('axis_color', 'black')
    axis_linewidth     = kwargs.get('axis_linewidth', 1.5)
    axis_arrow_style   = kwargs.get('axis_arrow_style', '->')
    axis_arrow_mutation = kwargs.get('axis_arrow_mutation', 15)
    axis_extend        = kwargs.get('axis_extend', 0.15)

    # Axis labels
    xlabel_right          = kwargs.get('xlabel_right', '')
    xlabel_left           = kwargs.get('xlabel_left', '')
    ylabel_top            = kwargs.get('ylabel_top', '')
    ylabel_bottom         = kwargs.get('ylabel_bottom', '')
    axis_label_fontsize   = kwargs.get('axis_label_fontsize', 12)
    axis_label_fontweight = kwargs.get('axis_label_fontweight', 'bold')
    axis_label_offset     = kwargs.get('axis_label_offset', 0.06)

    # Legend
    show_legend      = kwargs.get('show_legend', True)
    legend_title     = kwargs.get('legend_title', value_col)
    legend_loc       = kwargs.get('legend_loc', 'upper right')
    legend_bbox      = kwargs.get('legend_bbox', None)
    legend_fontsize  = kwargs.get('legend_fontsize', 10)
    legend_ncol      = kwargs.get('legend_ncol', 1)

    # Title
    chart_title      = kwargs.get('title', '')
    title_size       = kwargs.get('title_size', 14)

    # Misc
    startangle       = kwargs.get('startangle', 90)
    transparent_bg   = kwargs.get('transparent_bg', False)

    # ── Prepare data ──────────────────────────────────────────────────────
    scenario_names = list(data_dict.keys())
    all_categories = set()
    for df in data_dict.values():
        all_categories.update(df[value_col].dropna().unique())
    all_categories = sorted(all_categories)
    n_categories = len(all_categories)

    # Build per-quadrant proportions – each quadrant gets exactly 90°
    quad_sector_angles = []   # flat list of wedge sizes (degrees)
    quad_sector_colors = []   # flat list of colours
    quad_sector_labels = []   # flat list of (pct, quadrant_index)
    explode_flat = []         # flat list of explode values

    # Generate a colour map for categories if not provided
    if quadrant_colors is None:
        base_cmap = plt.cm.get_cmap(cmap_name)
        _auto_colors = [base_cmap(i) for i in range(n_categories)]
    else:
        _auto_colors = None

    for qi, (scenario, df) in enumerate(data_dict.items()):
        vc = df[value_col].value_counts()
        total = vc.sum()
        for ci, cat in enumerate(all_categories):
            count = vc.get(cat, 0)
            pct_of_quad = (count / total * 100) if total > 0 else 0
            angle = 90.0 * (count / total) if total > 0 else 0
            quad_sector_angles.append(angle)
            quad_sector_labels.append(pct_of_quad)
            explode_flat.append(explode_per_quad[qi])

            if quadrant_colors is not None:
                quad_sector_colors.append(quadrant_colors[qi][ci % len(quadrant_colors[qi])])
            else:
                quad_sector_colors.append(_auto_colors[ci])

    # ── Create figure ─────────────────────────────────────────────────────
    fig, ax = plt.subplots(figsize=figsize, dpi=dpi)
    ax.set_aspect('equal')

    if transparent_bg:
        fig.patch.set_alpha(0)
        ax.patch.set_alpha(0)

    # ── Draw the pie (donut wedges) ───────────────────────────────────────
    wedges, _ = ax.pie(
        quad_sector_angles,
        radius=outer_radius,
        startangle=startangle,
        colors=quad_sector_colors,
        explode=explode_flat,
        wedgeprops=dict(
            width=outer_radius - hole_radius,
            edgecolor=edgecolor,
            linewidth=edge_linewidth,
        ),
        counterclock=True,
    )

    # ── Percentage labels on wedges ───────────────────────────────────────
    if show_pct:
        for idx, (wedge, pct_val) in enumerate(zip(wedges, quad_sector_labels)):
            if pct_val < pct_threshold:
                continue
            # Compute label position at the mid-angle, mid-radius of the wedge
            theta1 = np.radians(wedge.theta1)
            theta2 = np.radians(wedge.theta2)
            mid_angle = (theta1 + theta2) / 2
            mid_r = (hole_radius + outer_radius) / 2

            # Account for explode offset
            exp = explode_flat[idx]
            cx = exp * np.cos(mid_angle)
            cy = exp * np.sin(mid_angle)

            x = cx + mid_r * np.cos(mid_angle)
            y = cy + mid_r * np.sin(mid_angle)
            ax.text(x, y, f'{pct_val:.1f}%',
                    ha='center', va='center',
                    fontsize=pct_fontsize, color=pct_color,
                    fontweight='bold')

    # ── Centre hole ───────────────────────────────────────────────────────
    centre_circle = plt.Circle(
        (0, 0), hole_radius,
        fc=hole_color, ec=hole_edgecolor, linewidth=hole_linewidth,
        zorder=5
    )
    ax.add_patch(centre_circle)

    # ── Quadrant scenario labels ──────────────────────────────────────────
    if show_quadrant_labels:
        # Mid-angles for quadrant centres (counter-clockwise from startangle)
        # With startangle=90: Q1 centre ≈ 135°, Q2 ≈ 225°, Q3 ≈ 315°, Q4 ≈ 45°
        # Actually with startangle=90 and counter-clockwise:
        #   Q1 starts at 90° and spans to 180° → mid = 135°
        #   Q2 starts at 180° and spans to 270° → mid = 225°
        #   Q3 starts at 270° and spans to 360° → mid = 315°
        #   Q4 starts at 0° and spans to 90° → mid = 45°  (but displayed as 360→450, mid=405≡45)
        quad_mid_angles_deg = [
            startangle + 45 + i * 90 for i in range(4)
        ]
        for qi, (name, mid_deg) in enumerate(zip(scenario_names, quad_mid_angles_deg)):
            rad = np.radians(mid_deg)
            r = outer_radius * quadrant_label_offset
            x = r * np.cos(rad) + explode_per_quad[qi] * np.cos(rad)
            y = r * np.sin(rad) + explode_per_quad[qi] * np.sin(rad)
            ax.text(x, y, name,
                    ha='center', va='center',
                    fontsize=quadrant_label_fontsize,
                    fontweight=quadrant_label_fontweight)

    # ── Coordinate axes (arrows) ──────────────────────────────────────────
    if show_axes:
        arrow_len = outer_radius + axis_extend
        # X-axis (horizontal)
        ax.annotate('', xy=(arrow_len, 0), xytext=(-arrow_len, 0),
                    arrowprops=dict(arrowstyle=axis_arrow_style,
                                   color=axis_color,
                                   lw=axis_linewidth,
                                   mutation_scale=axis_arrow_mutation))
        # Y-axis (vertical)
        ax.annotate('', xy=(0, arrow_len), xytext=(0, -arrow_len),
                    arrowprops=dict(arrowstyle=axis_arrow_style,
                                   color=axis_color,
                                   lw=axis_linewidth,
                                   mutation_scale=axis_arrow_mutation))

        # Axis labels at arrow tips
        lbl_r = arrow_len + axis_label_offset
        if xlabel_right:
            ax.text(lbl_r, 0, xlabel_right,
                    ha='left', va='center',
                    fontsize=axis_label_fontsize,
                    fontweight=axis_label_fontweight)
        if xlabel_left:
            ax.text(-lbl_r, 0, xlabel_left,
                    ha='right', va='center',
                    fontsize=axis_label_fontsize,
                    fontweight=axis_label_fontweight)
        if ylabel_top:
            ax.text(0, lbl_r, ylabel_top,
                    ha='center', va='bottom',
                    fontsize=axis_label_fontsize,
                    fontweight=axis_label_fontweight)
        if ylabel_bottom:
            ax.text(0, -lbl_r, ylabel_bottom,
                    ha='center', va='top',
                    fontsize=axis_label_fontsize,
                    fontweight=axis_label_fontweight)

    # ── Legend ────────────────────────────────────────────────────────────
    if show_legend:
        # One entry per category
        if quadrant_colors is not None:
            # Use first quadrant's colours as representative
            _leg_colors = [quadrant_colors[0][ci % len(quadrant_colors[0])]
                           for ci in range(n_categories)]
        else:
            _leg_colors = _auto_colors

        legend_handles = [
            mpatches.Patch(facecolor=_leg_colors[ci], edgecolor=edgecolor,
                           label=str(cat))
            for ci, cat in enumerate(all_categories)
        ]
        legend_kw = dict(
            handles=legend_handles,
            title=legend_title,
            loc=legend_loc,
            fontsize=legend_fontsize,
            framealpha=0.9,
            ncol=legend_ncol,
        )
        if legend_bbox is not None:
            legend_kw['bbox_to_anchor'] = legend_bbox
        ax.legend(**legend_kw)

    # ── Title ─────────────────────────────────────────────────────────────
    if chart_title:
        ax.set_title(chart_title, fontsize=title_size, fontweight='bold')

    # ── Clean up axes limits (account for explode + labels) ───────────────
    margin = outer_radius * quadrant_label_offset + 0.3
    ax.set_xlim(-margin, margin)
    ax.set_ylim(-margin, margin)
    ax.axis('off')

    plt.tight_layout()

    # ── Save ──────────────────────────────────────────────────────────────
    if figure_folder is not None and filename is not None:
        save_path = Path(figure_folder) / filename
        plt.savefig(str(save_path), bbox_inches='tight',
                    pad_inches=0, transparent=transparent_bg)

    if show:
        plt.show()

    return fig, ax


def _try_numeric(s):
    """Try to convert a string to int or float; return original if impossible."""
    try:
        v = float(s)
        return int(v) if v == int(v) else v
    except (ValueError, TypeError):
        return s

def _inv_std(x_std, j, scaler):
    """Inverse-standardise feature *j* using (mean, std) stored in *scaler*."""
    mu, sd = scaler[j]
    return x_std * sd + mu


def _make_diverging_norm(Z):
    """TwoSlopeNorm centred at 0; returns None when Z is constant."""
    vmax = max(abs(Z.min()), abs(Z.max()))
    if vmax == 0:
        return None
    return TwoSlopeNorm(vmin=-vmax, vcenter=0, vmax=vmax)


def _build_diverging_cmap(neg_colors, pos_colors, name="custom_div", n_bins=256):
    """Build a diverging ``LinearSegmentedColormap`` from two colour lists.

    Parameters
    ----------
    neg_colors : list[str | tuple]
        Colours for the *negative* half, ordered from most-negative → 0.
        E.g. ``["darkblue", "lightblue"]``.
    pos_colors : list[str | tuple]
        Colours for the *positive* half, ordered from 0 → most-positive.
        E.g. ``["lightyellow", "darkred"]``.
    n_bins : int
        Total number of discrete colour steps.
    """
    from matplotlib.colors import LinearSegmentedColormap
    all_colors = list(neg_colors) + list(pos_colors)
    return LinearSegmentedColormap.from_list(name, all_colors, N=n_bins)


def _resolve_cmap(cmap):
    """Accept a string / Colormap *or* a ``(neg_colors, pos_colors)`` tuple."""
    if isinstance(cmap, (list, tuple)) and len(cmap) == 2:
        first, second = cmap
        # If both elements are lists (of colours), build a diverging cmap.
        if isinstance(first, (list, tuple)) and isinstance(second, (list, tuple)):
            return _build_diverging_cmap(first, second)
    # Otherwise pass through (string name or Colormap instance).
    return cmap


def plot_partial_dependence(
    gam,
    feature_names,
    scaler=None,
    *,
    # ---------- feature selection ----------
    features=None,           # int | str | list  → None = all non-intercept terms
    # ---------- shared ----------
    ci_width=0.95,
    ylabel=None,             # None → auto "s(feature_name)"; str; or list[str] per term
    title_suffix="partial dependence (log-odds)",
    figsize_1d=(6, 4),
    # ---------- 1-D curve ----------
    line_color="tab:blue",
    line_width=2.0,
    line_label="partial dep.",
    ci_color="tab:blue",
    ci_alpha=0.2,
    ci_label="95 % CI",
    show_legend=True,        # False → hide legend on 1-D plots
    show_title=True,         # False → suppress all subplot / suptitle titles
    # ---------- 2-D / 3-D contour ----------
    cmap="RdBu_r",          # str | Colormap | (neg_colors, pos_colors)
    contour_levels=20,
    colorbar_label="effect on log-odds",
    colorbar_shrink=0.8,
    figsize_2d=(7, 5),
    figsize_3d_per_slice=5,
    figsize_3d_height=4,
    max_slices=6,
    # ---------- axis limits ----------
    xlim=None,               # (xmin, xmax) or None
    ylim=None,               # (ymin, ymax) or None
    # ---------- font sizes ----------
    title_fontsize=14,
    label_fontsize=12,
    label_fontweight="normal",   # "bold" to make axis labels bold
    tick_fontsize=10,
    suptitle_fontsize=14,
    # ---------- display ----------
    dpi=100,                 # figure screen dpi (plt.subplots dpi)
    # ---------- output / save ----------
    output_dir=None,         # str | Path  → directory for saved figures; None = don't save
    file_prefix="pdep",      # prefix for auto-generated filenames
    file_format="png",       # "png", "pdf", "svg", …
    save_dpi=300,            # resolution for saved figures
):
    """Plot GAM partial-dependence curves / surfaces.

    Parameters
    ----------
    gam : pygam GAM object
        Fitted GAM model.
    feature_names : list[str]
        Names for each feature column, in the same order as the training data.
    scaler : dict | None
        ``{feature_index: (mean, std)}`` used to inverse-standardise axes.
        Pass ``None`` to skip de-standardisation.
    features : int | str | list | None
        Which term(s) to plot.
        * ``None`` – plot every non-intercept term.
        * ``int``  – term index (position in ``gam.terms``).
        * ``str``  – feature name (looked up in *feature_names*).
        * ``list`` – a list mixing ints and/or strs.
    ci_width : float
        Width of the confidence interval (0–1).
    ylabel : None | str | list[str]
        Y-axis label for 1-D plots.

        * ``None`` (default) – auto-generate ``"s(feature_name)"``.
        * ``str``  – use the same label for every 1-D plot.
        * ``list[str]`` – one label per plotted 1-D term (in plotting order).
    title_suffix : str
        Suffix appended to figure titles.
    show_legend : bool
        Whether to show the legend on 1-D plots.
    show_title : bool
        Whether to display titles (subplot titles and suptitles).
    xlim, ylim : tuple | None
        Axis limits ``(min, max)`` applied to every subplot.
    dpi : int
        Screen resolution for displayed figures.
    figsize_1d, figsize_2d : tuple
        Figure sizes for 1-D and 2-D plots.
    figsize_3d_per_slice, figsize_3d_height : float
        Per-slice width and height for 3-D sliced contour grids.
    max_slices : int
        Max number of slices shown for 3-D tensor terms.
    line_color, line_width, line_label : str/float
        Styling of the 1-D partial-dependence curve.
    ci_color, ci_alpha, ci_label : str/float
        Shaded confidence-interval styling.
    cmap : str | Colormap | tuple[list, list]
        Colormap for 2-D / 3-D contour plots.  Can be:

        * a Matplotlib colormap name (``"RdBu_r"``) or ``Colormap`` object,
        * **a tuple of two colour-lists** ``(neg_colors, pos_colors)``
          to create a custom diverging colormap.  ``neg_colors`` runs from
          most-negative → 0; ``pos_colors`` from 0 → most-positive.
          Example: ``(["#2166ac", "#f7f7f7"], ["#f7f7f7", "#b2182b"])``.
    contour_levels : int
        Number of contour levels.
    colorbar_label : str
        Label shown beside the colour bar.
    colorbar_shrink : float
        Colour-bar shrink factor.
    title_fontsize, label_fontsize, tick_labelsize, suptitle_fontsize : int
        Font sizes.
    label_fontweight : str
        Font weight for axis labels (``"normal"``, ``"bold"``, etc.).
    output_dir : str | pathlib.Path | None
        Directory where figures are saved.  ``None`` (default) → don't save,
        only display.  The directory is created automatically if it does not
        exist.
    file_prefix : str
        Prefix prepended to every saved filename
        (e.g. ``"pdep"`` → ``"pdep_cost.png"``).
    file_format : str
        File extension / format passed to ``fig.savefig``
        (``"png"``, ``"pdf"``, ``"svg"``, …).
    save_dpi : int
        Resolution (dots per inch) for raster formats.
    """
    if scaler is None:
        scaler = {}

    # ---- resolve ylabel into an iterator ----
    # _ylabel_iter will be consumed one element at a time for each 1-D term.
    if ylabel is None:
        _ylabel_mode = "auto"     # will build "s(feature_name)" on the fly
        _ylabel_iter = iter([])   # unused sentinel
    elif isinstance(ylabel, str):
        _ylabel_mode = "fixed"
        _ylabel_fixed = ylabel
        _ylabel_iter = iter([])   # unused sentinel
    else:
        _ylabel_mode = "list"
        _ylabel_iter = iter(ylabel)

    # ---- resolve cmap (accept two colour lists) ----
    cmap = _resolve_cmap(cmap)

    # ---- prepare output directory ----
    if output_dir is not None:
        from pathlib import Path
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

    # ---- resolve *features* to a list of term indices ----
    if features is None:
        term_indices = list(range(len(gam.terms)))
    else:
        if not isinstance(features, (list, tuple)):
            features = [features]
        term_indices = []
        for f in features:
            if isinstance(f, str):
                term_indices.append(feature_names.index(f))
            else:
                term_indices.append(int(f))

    figs = []

    for term_i in term_indices:
        term = gam.terms[term_i]
        if term.isintercept:
            continue

        XX = gam.generate_X_grid(term=term_i)
        pdep, confi = gam.partial_dependence(term=term_i, X=XX, width=ci_width)
        idx = term.feature

        # ============================================================
        #  3-D tensor term  →  sliced contour plots
        # ============================================================
        if isinstance(idx, (list, tuple, np.ndarray)) and len(idx) == 3:
            x1, x2, x3 = XX[:, idx[0]], XX[:, idx[1]], XX[:, idx[2]]
            u1, u2, u3 = np.unique(x1), np.unique(x2), np.unique(x3)
            Z_full = pdep.reshape(len(u1), len(u2), len(u3))

            x1_plot = _inv_std(u1, idx[0], scaler) if idx[0] in scaler else u1
            x2_plot = _inv_std(u2, idx[1], scaler) if idx[1] in scaler else u2
            x3_plot = _inv_std(u3, idx[2], scaler) if idx[2] in scaler else u3

            norm = _make_diverging_norm(Z_full)
            n_slices = min(len(u3), max_slices)
            slice_indices = np.linspace(0, len(u3) - 1, n_slices, dtype=int)

            fig, axes = plt.subplots(
                1, n_slices,
                figsize=(figsize_3d_per_slice * n_slices, figsize_3d_height),
                dpi=dpi, squeeze=False,
            )
            for si, s_idx in enumerate(slice_indices):
                ax = axes[0, si]
                Z_slice = Z_full[:, :, s_idx]
                cf = ax.contourf(x2_plot, x1_plot, Z_slice,
                                 cmap=cmap, norm=norm, levels=contour_levels)
                ax.set_xlabel(feature_names[idx[1]], fontsize=label_fontsize, fontweight=label_fontweight)
                ax.set_ylabel(feature_names[idx[0]], fontsize=label_fontsize, fontweight=label_fontweight)
                if show_title:
                    ax.set_title(f"{feature_names[idx[2]]}={x3_plot[s_idx]:.2f}",
                                 fontsize=title_fontsize)
                ax.tick_params(labelsize=tick_fontsize)
                if xlim is not None:
                    ax.set_xlim(xlim)
                if ylim is not None:
                    ax.set_ylim(ylim)

            if show_title:
                fig.suptitle(
                    f"te({idx[0]},{idx[1]},{idx[2]}) {title_suffix}",
                    y=1.02, fontsize=suptitle_fontsize,
                )
            fig.colorbar(cf, ax=axes.ravel().tolist(),
                         label=colorbar_label, shrink=colorbar_shrink)
            plt.tight_layout()
            if output_dir is not None:
                fname = f"{file_prefix}_te{'_'.join(str(i) for i in idx)}.{file_format}"
                fig.savefig(output_dir / fname, dpi=save_dpi, bbox_inches="tight")
            figs.append(fig)
            plt.show()

        # ============================================================
        #  2-D tensor term  →  single contour plot
        # ============================================================
        elif isinstance(idx, (list, tuple, np.ndarray)) and len(idx) == 2:
            x1, x2 = XX[:, idx[0]], XX[:, idx[1]]
            u1, u2 = np.unique(x1), np.unique(x2)
            Z = pdep.reshape(len(u1), len(u2))

            x1_plot = _inv_std(u1, idx[0], scaler) if idx[0] in scaler else u1
            x2_plot = _inv_std(u2, idx[1], scaler) if idx[1] in scaler else u2

            norm = _make_diverging_norm(Z)
            fig, ax = plt.subplots(figsize=figsize_2d, dpi=dpi)
            cf = ax.contourf(x2_plot, x1_plot, Z,
                             cmap=cmap, norm=norm, levels=contour_levels)
            ax.set_xlabel(feature_names[idx[1]], fontsize=label_fontsize, fontweight=label_fontweight)
            ax.set_ylabel(feature_names[idx[0]], fontsize=label_fontsize, fontweight=label_fontweight)
            if show_title:
                ax.set_title(f"te({idx[0]},{idx[1]}) {title_suffix}",
                             fontsize=title_fontsize)
            ax.tick_params(labelsize=tick_fontsize)
            if xlim is not None:
                ax.set_xlim(xlim)
            if ylim is not None:
                ax.set_ylim(ylim)
            fig.colorbar(cf, ax=ax, label=colorbar_label, shrink=colorbar_shrink)
            plt.tight_layout()
            if output_dir is not None:
                fname = f"{file_prefix}_te{'_'.join(str(i) for i in idx)}.{file_format}"
                fig.savefig(output_dir / fname, dpi=save_dpi, bbox_inches="tight")
            figs.append(fig)
            plt.show()

        # ============================================================
        #  1-D smooth  →  curve + shaded CI
        # ============================================================
        else:
            x = XX[:, idx]
            x_plot = _inv_std(x, idx, scaler) if idx in scaler else x

            # ---- resolve y-label for this term ----
            if _ylabel_mode == "auto":
                _cur_ylabel = f"s({feature_names[idx]})"
            elif _ylabel_mode == "fixed":
                _cur_ylabel = _ylabel_fixed
            else:  # list
                _cur_ylabel = next(_ylabel_iter, f"s({feature_names[idx]})")

            fig, ax = plt.subplots(figsize=figsize_1d, dpi=dpi)
            ax.plot(x_plot, pdep,
                    color=line_color, linewidth=line_width, label=line_label)
            ax.fill_between(
                x_plot, confi[:, 0], confi[:, 1],
                color=ci_color, alpha=ci_alpha, label=ci_label,
            )
            ax.set_xlabel(feature_names[idx], fontsize=label_fontsize, fontweight=label_fontweight)
            ax.set_ylabel(_cur_ylabel, fontsize=label_fontsize, fontweight=label_fontweight)
            if show_title:
                ax.set_title(f"{term} {title_suffix}", fontsize=title_fontsize)
            ax.tick_params(labelsize=tick_fontsize)
            if xlim is not None:
                ax.set_xlim(xlim)
            if ylim is not None:
                ax.set_ylim(ylim)
            if show_legend:
                ax.legend(fontsize=label_fontsize)
            plt.tight_layout()
            if output_dir is not None:
                fname = f"{file_prefix}_{feature_names[idx]}.{file_format}"
                fig.savefig(output_dir / fname, dpi=save_dpi, bbox_inches="tight")
            figs.append(fig)
            plt.show()

    return figs